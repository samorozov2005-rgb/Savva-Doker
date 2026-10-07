package ru.savva.taskapi.task;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findAllByOrderByIdAsc();

    List<Task> findByOwnerUsernameOrderByIdAsc(String username);
}
