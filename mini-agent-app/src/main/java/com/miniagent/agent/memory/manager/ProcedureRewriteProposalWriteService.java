package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ProcedureRewriteProposalWriteService {
    private final ProcedureRewriteProposalInsertService insertService;

    public ProcedureRewriteProposalWriteService(ProcedureRewriteProposalInsertService insertService) {
        this.insertService = insertService;
    }

    public boolean insertIfAbsent(ProcedureRewriteProposalEntity proposal) {
        try {
            insertService.insert(proposal);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}
