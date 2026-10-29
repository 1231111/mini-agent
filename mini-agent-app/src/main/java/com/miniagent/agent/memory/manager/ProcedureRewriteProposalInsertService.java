package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.repository.ProcedureRewriteProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcedureRewriteProposalInsertService {
    private final ProcedureRewriteProposalRepository proposals;

    public ProcedureRewriteProposalInsertService(ProcedureRewriteProposalRepository proposals) {
        this.proposals = proposals;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(ProcedureRewriteProposalEntity proposal) {
        proposals.saveAndFlush(proposal);
    }
}
