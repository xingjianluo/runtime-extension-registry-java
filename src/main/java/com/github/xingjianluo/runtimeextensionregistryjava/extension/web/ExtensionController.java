package com.github.xingjianluo.runtimeextensionregistryjava.extension.web;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryPage;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.ExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 扩展注册中心 HTTP 接口。
 *
 * <ul>
 *   <li>{@code GET  /api/extensions}：当前世代快照（全部扩展状态与配置）</li>
 *   <li>{@code POST /api/extensions/changes}：提交一批原子变更（启停/配置/移除）</li>
 *   <li>{@code POST /api/extensions/{id}/invoke}：在当前世代执行某扩展</li>
 *   <li>{@code GET  /api/extensions/history}：历史版本列表（编号/类型/差异扩展）</li>
 *   <li>{@code GET  /api/extensions/history/{version}}：取某历史版本（含差异与完整快照）</li>
 *   <li>{@code POST /api/extensions/history/{version}/rollback}：原子回退到某历史版本</li>
 * </ul>
 * 新扩展的注册（携带工厂）仅开放编程式 API；HTTP 仅覆盖运行时热更新操作。
 */
@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

    private static final Logger log = LoggerFactory.getLogger(ExtensionController.class);

    private final ExtensionRegistry registry;

    public ExtensionController(ExtensionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public RegistrySnapshot list() {
        return registry.snapshot();
    }

    @GetMapping("/{id}")
    public ExtensionStatusView getOne(@PathVariable String id) {
        // 返回与列表中一致的状态视图；不存在由全局处理器归一化为 404。
        return registry.status(id)
                .orElseThrow(() -> new ExtensionException(ErrorCode.EXTENSION_NOT_FOUND,
                        "extension not found: " + id, Map.of("extensionId", id)));
    }

    @PostMapping("/changes")
    public ChangeOutcome change(@RequestBody List<ChangeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "changes must contain at least one request", Map.of());
        }
        List<ChangeCommand> commands = new ArrayList<>();
        for (ChangeRequest request : requests) {
            // 打印输入配置与判定依据，满足可解释性与测试日志要求。
            log.info("change request received: action={}, extensionId={}, enabled={}, configKeys={}",
                    request.action(), request.extensionId(), request.enabled(),
                    request.config() == null ? null : request.config().keySet());
            commands.add(toCommand(request));
        }
        return registry.apply(new ChangeSet(commands));
    }

    @PostMapping("/{id}/invoke")
    public ExtensionResult invoke(@PathVariable String id,
                                  @RequestBody(required = false) Map<String, Object> input) {
        log.info("invoke received: extensionId={}, inputKeys={}",
                id, input == null ? null : input.keySet());
        return registry.invoke(new ExtensionInvocation(id, input));
    }

    @GetMapping("/history")
    public HistoryPage history() {
        return registry.history();
    }

    @GetMapping("/history/{version}")
    public HistoryVersion historyVersion(@PathVariable long version) {
        log.info("history version query: version={}", version);
        // 不存在或已被保留上限淘汰，统一归一化为 404 HISTORY_VERSION_NOT_FOUND（不返回空、不报 500）。
        return registry.historyVersion(version)
                .orElseThrow(() -> new ExtensionException(ErrorCode.HISTORY_VERSION_NOT_FOUND,
                        "history version not found or already evicted: " + version,
                        Map.of("version", version)));
    }

    @PostMapping("/history/{version}/rollback")
    public ChangeOutcome rollback(@PathVariable long version) {
        log.info("rollback request received: targetVersion={}", version);
        return registry.rollback(version);
    }

    private static ChangeCommand toCommand(ChangeRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "change request requires an action (enable|disable|update-config|remove)", Map.of());
        }
        String id = request.extensionId();
        if (id == null || id.isBlank()) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "change request requires extensionId", Map.of("action", request.action()));
        }
        return switch (request.action().trim().toLowerCase()) {
            case "enable" -> new ChangeCommand.SetEnabled(id, true);
            case "disable" -> new ChangeCommand.SetEnabled(id, false);
            case "update-config" -> {
                if (request.config() == null) {
                    throw new ExtensionException(ErrorCode.BAD_REQUEST,
                            "update-config requires config", Map.of("extensionId", id));
                }
                yield new ChangeCommand.UpdateConfig(id, request.config());
            }
            case "remove" -> new ChangeCommand.RemoveExtension(id);
            default -> throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "unknown action: " + request.action(),
                    Map.of("extensionId", id, "action", String.valueOf(request.action())));
        };
    }
}
