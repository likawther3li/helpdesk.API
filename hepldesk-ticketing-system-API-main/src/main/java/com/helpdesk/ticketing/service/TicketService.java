package com.helpdesk.ticketing.service;

import com.helpdesk.ticketing.entity.*;
import com.helpdesk.ticketing.enums.Priority;
import com.helpdesk.ticketing.enums.TicketStatus;
import com.helpdesk.ticketing.exception.InvalidStatusTransitionException;
import com.helpdesk.ticketing.exception.ResourceNotFoundException;
import com.helpdesk.ticketing.repository.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TicketService {
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final TicketStatusLogRepository ticketStatusLogRepository;
    private static final Map<TicketStatus, Set<TicketStatus>> VALID_TRANSITIONS = Map.of(
            TicketStatus.OPEN, Set.of(TicketStatus.IN_PROGRESS),
            TicketStatus.IN_PROGRESS, Set.of(TicketStatus.RESOLVED),
            TicketStatus.RESOLVED, Set.of(TicketStatus.CLOSED, TicketStatus.REOPENED),
            TicketStatus.REOPENED, Set.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED),
            TicketStatus.CLOSED, Set.of()
    );

    public TicketService(TicketRepository ticketRepository, UserRepository userRepository, AgentRepository agentRepository, SlaRuleRepository slaRuleRepository, TicketStatusLogRepository ticketStatusLogRepository) {
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.agentRepository = agentRepository;
        this.slaRuleRepository = slaRuleRepository;
        this.ticketStatusLogRepository = ticketStatusLogRepository;
    }

    @Transactional
    public Ticket createTicket(Ticket ticket, Long userId, Long slaRuleId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));

        SlaRule slaRule = slaRuleRepository.findById(slaRuleId).orElseThrow(() -> new ResourceNotFoundException("SLA Rule not found"));

        ticket.setUser(user);
        ticket.setSlaRule(slaRule);
        ticket.setStatus(TicketStatus.OPEN);

        Ticket savedTicket = ticketRepository.save(ticket);

        TicketStatusLog log = new TicketStatusLog();
        log.setTicket(savedTicket);
        log.setFromStatus(null);
        log.setToStatus(TicketStatus.OPEN);
        log.setChangedAt(LocalDateTime.now());

        ticketStatusLogRepository.save(log);

        return savedTicket;
    }

    public List<Ticket> getAllTickets() {
        return ticketRepository.findAll();
    }

    public Ticket getTicketById(Long ticketId) {
        return ticketRepository.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    public Ticket assignAgent(Long ticketId, Long agentId) {
        Ticket ticket = getTicketById(ticketId);

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new InvalidStatusTransitionException("Cannot assign agent to a closed ticket");
        }

        Agent agent = agentRepository.findById(agentId).orElseThrow(() -> new ResourceNotFoundException("Agent not found"));

        ticket.setAgent(agent);
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket updateStatus(Long ticketId, TicketStatus newStatus) {

        Ticket ticket = getTicketById(ticketId);

        TicketStatus oldStatus = ticket.getStatus();

        if (!isValidTransition(oldStatus, newStatus)) {
            throw new InvalidStatusTransitionException("Invalid status transition from " + oldStatus + " to " + newStatus);
        }

        ticket.setStatus(newStatus);

        if (newStatus == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(LocalDateTime.now());
        }

        if (newStatus == TicketStatus.CLOSED) {
            ticket.setClosedAt(LocalDateTime.now());
        }

        Ticket savedTicket = ticketRepository.save(ticket);

        TicketStatusLog log = new TicketStatusLog();
        log.setTicket(savedTicket);
        log.setFromStatus(oldStatus);
        log.setToStatus(newStatus);
        log.setChangedAt(LocalDateTime.now());

        ticketStatusLogRepository.save(log);

        return savedTicket;
    }

    private boolean isValidTransition(TicketStatus oldStatus, TicketStatus newStatus) {
        return VALID_TRANSITIONS.getOrDefault(oldStatus, Set.of()).contains(newStatus);
    }

    public List<Ticket> getOverdueTickets() {
        LocalDateTime now = LocalDateTime.now();

        return ticketRepository.findAll()
                .stream()
                .filter(ticket -> ticket.getStatus() != TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.CLOSED)
                .filter(ticket -> ticket.getCreatedAt() != null)
                .filter(ticket -> ticket.getSlaRule() != null)
                .filter(ticket -> ticket.getCreatedAt()
                        .plusHours(ticket.getSlaRule().getTargetHours())
                        .isBefore(now))
                .toList();
    }

    public double getAverageResolutionTimeInHours() {
        List<Ticket> resolvedTickets = ticketRepository.findAll()
                .stream()
                .filter(ticket -> ticket.getResolvedAt() != null)
                .filter(ticket -> ticket.getCreatedAt() != null)
                .toList();

        if (resolvedTickets.isEmpty()) {
            return 0;
        }

        double totalHours = resolvedTickets.stream().mapToDouble(ticket -> java.time.Duration.between(ticket.getCreatedAt(), ticket.getResolvedAt()).toMinutes() / 60.0).sum();

        return totalHours / resolvedTickets.size();
    }

    public List<Ticket> getTickets(TicketStatus status, Priority priority, String category, Long assignedTo) {
        Specification<Ticket> specification = Specification.where(null);

        if (status != null) {
            specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("status"), status));
        }

        if (priority != null) {
            specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("priority"), priority));
        }

        if (category != null) {
            specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("category"), category));
        }

        if (assignedTo != null) {
            specification = specification.and((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("agent").get("agentId"), assignedTo));
        }

        return ticketRepository.findAll(specification);
    }

    public long getTotalTickets() {
        return ticketRepository.count();
    }

    public long getOpenTickets() {
        return ticketRepository.countByStatus(TicketStatus.OPEN);
    }

    public long getClosedTickets() {
        return ticketRepository.countByStatus(TicketStatus.CLOSED);
    }
}
