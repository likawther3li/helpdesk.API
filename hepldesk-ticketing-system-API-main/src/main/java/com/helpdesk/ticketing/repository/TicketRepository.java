package com.helpdesk.ticketing.repository;
import com.helpdesk.ticketing.entity.Ticket;
import com.helpdesk.ticketing.enums.Priority;
import com.helpdesk.ticketing.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket>{
    List<Ticket> findByStatus(TicketStatus status);
    long countByStatus(TicketStatus status);

    List<Ticket> findByPriority(Priority priority);
    List<Ticket> findByCategory(String category);

    List<Ticket> findByAgentAgentId(Long agentId);
}
