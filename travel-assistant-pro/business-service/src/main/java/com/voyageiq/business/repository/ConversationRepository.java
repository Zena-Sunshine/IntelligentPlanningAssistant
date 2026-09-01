package com.voyageiq.business.repository;

import com.voyageiq.business.domain.Conversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    Page<Conversation> findByUserIdAndDeletedAtIsNull(String userId, Pageable pageable);
    Page<Conversation> findByUserIdAndDeletedAtIsNullAndTitleContainingIgnoreCase(
            String userId, String query, Pageable pageable);
    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(String id, String userId);
}

