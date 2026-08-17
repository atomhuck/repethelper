package ru.repethelper.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.repethelper.domain.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.repethelper.domain.Role;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    @Query("""
            select u from User u
            where (:role is null or u.role = :role)
              and (:state is null or :state = ''
                   or (:state = 'active' and u.enabled = true)
                   or (:state = 'blocked' and u.enabled = false and u.deletionScheduledAt is null)
                   or (:state = 'deleting' and u.deletionScheduledAt is not null))
              and (:q is null or :q = '' or str(u.id) = :q
                   or lower(u.username) like lower(concat('%', :q, '%'))
                   or lower(u.displayName) like lower(concat('%', :q, '%'))
                   or lower(coalesce(u.email, '')) like lower(concat('%', :q, '%')))
            """)
    Page<User> searchForAdmin(@Param("q") String q, @Param("role") Role role, @Param("state") String state, Pageable pageable);
}
