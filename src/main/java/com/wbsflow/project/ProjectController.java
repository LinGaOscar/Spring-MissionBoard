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
    private final UserRepository userRepository;

    @GetMapping("/projects")
    public String projectsPage() {
        return "project/list";
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

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
