package com.miniagent.memoryservice.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.MemoryApi.AssembleRequest;
import com.miniagent.memory.contract.MemoryApi.AssembleResponse;
import com.miniagent.memory.contract.MemoryApi.ChangeView;
import com.miniagent.memory.contract.MemoryApi.FeedbackRequest;
import com.miniagent.memory.contract.MemoryApi.ItemHealth;
import com.miniagent.memory.contract.MemoryApi.ItemImport;
import com.miniagent.memory.contract.MemoryApi.ItemPage;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ItemWrite;
import com.miniagent.memory.contract.MemoryApi.LockRequest;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatch;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatchResult;
import com.miniagent.memory.contract.MemoryApi.ProposalView;
import com.miniagent.memory.contract.MemoryApi.RevertRequest;
import com.miniagent.memory.contract.MemoryApi.ReviewRequest;
import com.miniagent.memory.contract.MemoryApi.RevisionView;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memory.port.ProjectAuthorizationCommand;
import com.miniagent.memory.port.ProjectAuthorizationResult;
import com.miniagent.memoryservice.evolution.ContextAssembler;
import com.miniagent.memoryservice.evolution.ExperimentService;
import com.miniagent.memoryservice.evolution.ItemService;
import com.miniagent.memoryservice.evolution.OutcomeIngestor;
import com.miniagent.memoryservice.store.ProjectAccess;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ProposalStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static com.miniagent.memoryservice.web.ServiceTokenFilter.PRINCIPAL_ATTR;

/** App 角色（服务令牌 role=app）调用的记忆接口。用户主体只取自令牌，不取自报文。 */
@RestController
public class MemoryController {
    private static final String ITEM = MemoryApi.ITEMS + "/{id:\\d+}";
    private static final String PROPOSAL = MemoryApi.PROPOSALS + "/{id:\\d+}";
    private static final String DEFAULT_PAGE = "50";

    private final ContextAssembler assembler;
    private final OutcomeIngestor ingestor;
    private final ItemService itemService;
    private final ExperimentService experiments;
    private final ProposalStore proposals;
    private final ProjectAccess projects;

    public MemoryController(ContextAssembler assembler, OutcomeIngestor ingestor,
                            ItemService itemService, ExperimentService experiments,
                            ProposalStore proposals, ProjectAccess projects) {
        this.assembler = assembler;
        this.ingestor = ingestor;
        this.itemService = itemService;
        this.experiments = experiments;
        this.proposals = proposals;
        this.projects = projects;
    }

    @PostMapping(MemoryApi.ASSEMBLE)
    public ApiResponse<AssembleResponse> assemble(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @RequestBody AssembleRequest request) {
        return ApiResponse.ok(assembler.assemble(principal, request));
    }

    /**
     * 结果 outbox 投递：系统调用（令牌可不带用户），事件里的用户由服务端按条目归属校验；
     * 令牌带租户时，整批事件必须属于该租户。
     */
    @PostMapping(MemoryApi.OUTCOMES)
    public ApiResponse<OutcomeBatchResult> outcomes(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @RequestBody OutcomeBatch batch) {
        String tenant = principal.tenantId();
        boolean foreign = tenant != null && batch != null && batch.events() != null
                && batch.events().stream().anyMatch(e -> e != null && !tenant.equals(e.tenantId()));
        if (foreign) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "结果事件的租户与令牌不一致");
        }
        boolean foreignUser = principal.hasUser() && batch != null && batch.events() != null
                && batch.events().stream().anyMatch(e -> e != null
                && !Objects.equals(principal.userId(), e.userId()));
        if (foreignUser) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "结果事件用户与令牌不一致");
        }
        return ApiResponse.ok(ingestor.ingest(batch));
    }

    @PostMapping(MemoryApi.FEEDBACK)
    public ApiResponse<Integer> feedback(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                         @RequestBody FeedbackRequest request) {
        return ApiResponse.ok(ingestor.feedback(principal, request));
    }

    @GetMapping(MemoryApi.ITEMS)
    public ApiResponse<ItemPage> list(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                      @RequestParam(required = false) String name,
                                      @RequestParam(required = false) Long cursor,
                                      @RequestParam(defaultValue = DEFAULT_PAGE) int limit) {
        return ApiResponse.ok(itemService.list(principal, name, cursor, limit));
    }

    @PostMapping(MemoryApi.ITEMS)
    public ApiResponse<ItemView> upsert(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                        @RequestBody ItemWrite write) {
        return ApiResponse.ok(itemService.upsert(principal, write));
    }

    /** 迁移导入只能写入当前用户自己的 USER 域数据。 */
    @PostMapping(MemoryApi.ITEMS_IMPORT)
    public ApiResponse<ItemView> importItem(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                            @RequestBody ItemImport request) {
        if (request == null || !principal.hasUser()
                || !Objects.equals(principal.tenantId(), request.tenantId())
                || !Objects.equals(principal.userId(), request.ownerUserId())
                || request.item() == null
                || request.item().scopeType() != com.miniagent.memory.contract.MemoryApi.ScopeType.USER) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "只能为令牌所属用户导入 USER 域 SOP");
        }
        return ApiResponse.ok(itemService.importItem(request));
    }

    @GetMapping(ITEM)
    public ApiResponse<ItemView> get(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                     @PathVariable long id) {
        return ApiResponse.ok(itemService.get(principal, id));
    }

    @DeleteMapping(ITEM)
    public ApiResponse<Void> delete(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                    @PathVariable long id) {
        itemService.delete(principal, id);
        return ApiResponse.ok();
    }

    @GetMapping(ITEM + "/revisions")
    public ApiResponse<List<RevisionView>> revisions(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal, @PathVariable long id) {
        return ApiResponse.ok(itemService.revisions(principal, id));
    }

    @GetMapping(ITEM + "/activations")
    public ApiResponse<List<ChangeView>> activations(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal, @PathVariable long id) {
        return ApiResponse.ok(itemService.history(principal, id));
    }

    @GetMapping(ITEM + "/health")
    public ApiResponse<ItemHealth> health(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                          @PathVariable long id) {
        return ApiResponse.ok(itemService.health(principal, id));
    }

    @PostMapping(ITEM + ":revert")
    public ApiResponse<ItemView> revert(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                        @PathVariable long id,
                                        @RequestBody RevertRequest request) {
        return ApiResponse.ok(itemService.revert(principal, id, request.revisionNo()));
    }

    @PostMapping(ITEM + ":restore")
    public ApiResponse<ItemView> restore(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                         @PathVariable long id) {
        return ApiResponse.ok(itemService.restore(principal, id));
    }

    @PostMapping(ITEM + ":lock")
    public ApiResponse<ItemView> lock(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                      @PathVariable long id, @RequestBody LockRequest request) {
        return ApiResponse.ok(itemService.lock(principal, id, request.locked()));
    }

    @GetMapping(MemoryApi.CHANGES)
    public ApiResponse<List<ChangeView>> changes(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal, @RequestParam Instant since) {
        return ApiResponse.ok(itemService.changes(principal, since));
    }

    @GetMapping(MemoryApi.PROPOSALS)
    public ApiResponse<List<ProposalView>> proposals(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @RequestParam(required = false) ProposalState state,
            @RequestParam(defaultValue = DEFAULT_PAGE) int limit) {
        return ApiResponse.ok(experiments.list(principal, state, limit));
    }

    @GetMapping(PROPOSAL)
    public ApiResponse<ProposalView> proposal(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal, @PathVariable long id) {
        return ApiResponse.ok(experiments.view(visibleProposal(principal, id)));
    }

    @PostMapping(PROPOSAL + ":approve")
    public ApiResponse<ProposalView> approve(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal, @PathVariable long id,
            @RequestBody(required = false) ReviewRequest request) {
        ProposalStore.Proposal p = visibleProposal(principal, id);
        return ApiResponse.ok(experiments.approve(principal,
                itemService.requireVisible(principal, p.itemId()), id, note(request)));
    }

    @PostMapping(PROPOSAL + ":reject")
    public ApiResponse<ProposalView> reject(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal, @PathVariable long id,
            @RequestBody(required = false) ReviewRequest request) {
        ProposalStore.Proposal p = visibleProposal(principal, id);
        return ApiResponse.ok(experiments.reject(principal,
                itemService.requireVisible(principal, p.itemId()), id, note(request)));
    }

    /** 项目授权快照只能由该项目 owner 身份提交。 */
    @PutMapping(MemoryApi.PROJECTS + "/{projectId}/authorization")
    public ApiResponse<ProjectAuthorizationResult> authorize(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @PathVariable String projectId, @RequestBody ProjectAuthorizationCommand command) {
        if (command == null || !projectId.equals(command.projectId())
                || !principal.hasUser()
                || !Objects.equals(principal.tenantId(), command.tenantId())
                || !Objects.equals(principal.userId(), command.ownerUserId())) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "项目授权命令与路径或租户不一致");
        }
        return ApiResponse.ok(projects.synchronize(command));
    }

    /** 用户注销级联删除只能清理当前令牌主体的数据。 */
    @DeleteMapping(MemoryApi.SUBJECTS + "/{userId:\\d+}")
    public ApiResponse<Integer> erase(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                      @PathVariable long userId) {
        if (!principal.hasUser() || !Objects.equals(principal.userId(), userId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "只能清理令牌所属用户的数据");
        }
        return ApiResponse.ok(itemService.eraseUser(principal.tenantId(), userId));
    }

    private ProposalStore.Proposal visibleProposal(Principal principal, long id) {
        ProposalStore.Proposal p = proposals.find(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMORY_PROPOSAL_STATE_INVALID));
        itemService.requireVisible(principal, p.itemId());
        return p;
    }

    private static String note(ReviewRequest request) {
        return request == null ? null : request.note();
    }
}
