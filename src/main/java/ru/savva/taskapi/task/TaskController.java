package ru.savva.taskapi.task;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.savva.taskapi.user.User;
import ru.savva.taskapi.user.UserRepository;

@RestController
@RequestMapping("/tasks")
public class TaskController {
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskController(TaskRepository taskRepository, UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request, Principal principal) {
        User owner = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Task saved = taskRepository.save(
                new Task(request.title(), request.description(), request.completed(), owner));
        return ResponseEntity.created(URI.create("/tasks/" + saved.getId())).body(TaskResponse.from(saved));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<TaskResponse> all() {
        return taskRepository.findAllByOrderByIdAsc().stream().map(TaskResponse::from).toList();
    }

    @GetMapping("/my")
    @Transactional(readOnly = true)
    public List<TaskResponse> mine(Principal principal) {
        return taskRepository.findByOwnerUsernameOrderByIdAsc(principal.getName())
                .stream().map(TaskResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        if (!task.getOwner().getUsername().equals(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can delete this task");
        }
        taskRepository.delete(task);
        return ResponseEntity.noContent().build();
    }
}
