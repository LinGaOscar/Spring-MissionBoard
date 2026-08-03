package com.wbsflow.project;

import com.wbsflow.common.ApiResponse;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    @GetMapping("/projects")
    public String projectsPage() {
        return "project/list";
    }

    @GetMapping("/projects/{id}")
    public String detail(@PathVariable Long id, org.springframework.ui.Model model, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            return "redirect:/projects";
        }
        model.addAttribute("projectId", id);
        model.addAttribute("canWrite", projectService.canWrite(id, user));
        return "project/detail";
    }

    @GetMapping("/api/projects")
    @ResponseBody
    public ApiResponse<List<ProjectDto.Response>> list(
            @RequestParam(defaultValue = "false") boolean archived, Principal principal) {
        User user = currentUser(principal);
        List<ProjectDto.Response> result = projectService.listForUser(user, archived)
            .stream().map(ProjectDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/projects")
    @ResponseBody
    public ApiResponse<ProjectDto.Response> create(
            @Valid @RequestBody ProjectDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        Project project = projectService.createProject(req.name(), req.description(), user);
        return ApiResponse.ok(ProjectDto.Response.from(project));
    }

    @GetMapping("/api/projects/{id}")
    @ResponseBody
    public ApiResponse<ProjectDto.Response> get(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            throw new SecurityException("無存取權限");
        }
        return ApiResponse.ok(ProjectDto.Response.from(projectService.getById(id)));
    }

    @PatchMapping("/api/projects/{id}/archive")
    @ResponseBody
    public ApiResponse<Void> archive(@PathVariable Long id, Principal principal) {
        projectService.archiveProject(id, currentUser(principal));
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{id}/unarchive")
    @ResponseBody
    public ApiResponse<Void> unarchive(@PathVariable Long id, Principal principal) {
        projectService.unarchiveProject(id, currentUser(principal));
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/projects/{id}/members")
    @ResponseBody
    public ApiResponse<List<ProjectDto.MemberResponse>> members(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            throw new SecurityException("無存取權限");
        }
        List<ProjectDto.MemberResponse> result = projectMemberRepository.findByIdProjectId(id)
            .stream().map(ProjectDto.MemberResponse::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/projects/{id}/members")
    @ResponseBody
    public ApiResponse<Void> addMember(@PathVariable Long id,
            @RequestBody ProjectDto.MemberRequest req, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限管理此專案成員");
        }
        projectService.addMember(id, req.userId(), user);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/projects/{id}/members/{userId}")
    @ResponseBody
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long userId, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限管理此專案成員");
        }
        projectService.removeMember(id, userId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/projects/{id}/owner")
    @ResponseBody
    public ApiResponse<Void> changeOwner(@PathVariable Long id,
            @RequestBody ProjectDto.MemberRequest req, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限變更負責人");
        }
        projectService.changeOwner(id, req.userId(), user);
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
