package com.forgepilot.notification;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    Optional<NotificationChannel> findByProjectIdAndChannel(long projectId,
            NotificationChannelType channel);

    void deleteByProjectIdAndChannel(long projectId, NotificationChannelType channel);
}
