package com.wbsflow.project;

import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import com.wbsflow.wbs.WbsNodeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final WbsNodeRepository wbsNodeRepository;

    // 統一拋 EntityNotFoundException，controller 層交給 GlobalExceptionHandler 轉 404
    public Project getById(Long id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("專案不存在"));
    }

    public boolean isMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) return false;
        return projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId);
    }

    // 讀取權限：DIRECTOR 跨科唯讀、SECTION_CHIEF 科內全權、PROJECT_LEADER/PROJECT_MEMBER 僅參與專案
    public boolean canRead(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        return switch (user.getRole()) {
            case DIRECTOR -> true;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 寫入權限：封存專案全員唯讀；DIRECTOR 一律唯讀；其餘同讀取權限範圍
    public boolean canWrite(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        if (project.isArchived()) return false;
        return switch (user.getRole()) {
            case DIRECTOR -> false;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 封存/還原專用權限：邏輯與 canWrite 相同但不看 archived 旗標，
    // 否則已封存的專案因 canWrite 對封存旗標永遠回 false，會變成沒有人能還原
    public boolean canArchive(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        return switch (user.getRole()) {
            case DIRECTOR -> false;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 任何已登入使用者皆可建立；section 自動代入建立者部門，建立者自動成為 owner 與成員
    @Transactional
    public Project createProject(String name, String description, User creator) {
        if (creator.getDepartment() == null) {
            throw new IllegalArgumentException("使用者無所屬部門，無法建立專案");
        }
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setSection(creator.getDepartment());
        project.setOwner(creator);
        project.setCreatedBy(creator);
        Project saved = projectRepository.save(project);
        addMember(saved.getId(), creator.getId(), creator);
        return saved;
    }

    // 依角色回傳可見範圍：DIRECTOR 全部、SECTION_CHIEF 同科、LEADER/MEMBER 僅參與
    @Transactional(readOnly = true)
    public List<Project> listForUser(User user, boolean archived) {
        return switch (user.getRole()) {
            case DIRECTOR -> projectRepository.findByArchived(archived);
            case SECTION_CHIEF -> projectRepository.findBySectionIdAndArchived(user.getDepartment().getId(), archived);
            case PROJECT_LEADER, PROJECT_MEMBER -> projectRepository.findByMemberUserIdAndArchived(user.getId(), archived);
        };
    }

    // 封存冪等：已封存的專案再封存一次不報錯
    @Transactional
    public void archiveProject(Long projectId, User caller) {
        if (!canArchive(projectId, caller)) {
            throw new SecurityException("無權限封存此專案");
        }
        Project project = getById(projectId);
        project.setArchived(true);
        projectRepository.save(project);
    }

    // 還原冪等：未封存的專案再還原一次不報錯
    @Transactional
    public void unarchiveProject(Long projectId, User caller) {
        if (!canArchive(projectId, caller)) {
            throw new SecurityException("無權限還原此專案");
        }
        Project project = getById(projectId);
        project.setArchived(false);
        projectRepository.save(project);
    }

    // 內部共用：冪等新增成員，不含權限檢查——呼叫端（controller 或 createProject/changeOwner）已確認過權限，
    // 若在此重複檢查 canWrite，會在「建立者尚未是成員」的時間點卡住建立流程本身
    @Transactional
    public void addMember(Long projectId, Long userId, User assignedBy) {
        if (projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId)) return;
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(projectId, user.getId()));
        pm.setAssignedBy(assignedBy);
        projectMemberRepository.save(pm);
    }

    // 移除成員時連動清除其在該專案下的節點指派（CLAUDE.md 核心規則，即使節點 CRUD 尚未實作也要保證）
    @Transactional
    public void removeMember(Long projectId, Long userId) {
        projectMemberRepository.deleteById(new ProjectMemberId(projectId, userId));
        // 顯式 flush：deleteById 找到的實體常已存在於一級快取（先前查詢留下），
        // 刪除動作會延後到 flush 才真正送出 DELETE；同交易內若緊接著查詢
        // project_members（本方法呼叫端或測試斷言）不保證觸發 auto-flush，
        // 會讀到「看似還沒刪除」的結果，故此處立即 flush 確保刪除立即可見
        projectMemberRepository.flush();
        wbsNodeRepository.clearAssigneeForUserInProject(projectId, userId);
    }

    // 換負責人：若新 owner 尚未是成員自動補加，確保新 owner 一定對自己的專案有 canWrite
    @Transactional
    public void changeOwner(Long projectId, Long newOwnerId, User actor) {
        Project project = getById(projectId);
        User newOwner = userRepository.findById(newOwnerId)
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        project.setOwner(newOwner);
        projectRepository.save(project);
        addMember(projectId, newOwnerId, actor);
    }

    private boolean sameSection(Project project, User user) {
        if (project.getSection() == null || user.getDepartment() == null) return false;
        return project.getSection().getId().equals(user.getDepartment().getId());
    }
}
