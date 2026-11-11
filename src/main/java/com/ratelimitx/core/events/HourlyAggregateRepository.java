package com.ratelimitx.core.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HourlyAggregateRepository extends JpaRepository<HourlyAggregate, Long> {

    Optional<HourlyAggregate> findByHourAndRoleAndAlgorithm(Instant hour, String role, String algorithm);

    List<HourlyAggregate> findAllByOrderByHourDescRoleAscAlgorithmAsc();
}
