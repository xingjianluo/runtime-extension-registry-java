package com.github.xingjianluo.runtimeextensionregistryjava.web;

import com.github.xingjianluo.runtimeextensionregistryjava.core.ExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.core.StateChangeRequest;
import com.github.xingjianluo.runtimeextensionregistryjava.core.StateItem;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.DependencySpec;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionSnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.ChangeItemDto;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.ChangeRequestDto;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.DependencyDto;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.DependencyView;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.ExecuteRequest;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.ExecuteResponse;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.ExtensionView;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.RegisterRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST facade of the extension registry. All errors are normalized via GlobalExceptionHandler. */
@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

    private final ExtensionRegistry registry;

    public ExtensionController(ExtensionRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExtensionView register(@RequestBody RegisterRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new ExtensionException(ExtensionErrorCode.INVALID_REQUEST,
                    "registration requires id/version/kind", Map.of());
        }
        List<DependencySpec> dependencies;
        try {
            dependencies = request.dependencies() == null ? List.of()
                    : request.dependencies().stream()
                            .map((DependencyDto d) -> DependencySpec.parse(d.extensionId(), d.versionRange()))
                            .toList();
        } catch (ExtensionException e) {
            throw e;
        }
        ExtensionDefinition definition = new ExtensionDefinition(request.id(), request.version(),
                request.kind(), dependencies, request.config());
        registry.register(definition);
        return toView(registry.snapshot(request.id()));
    }

    @GetMapping
    public List<ExtensionView> list() {
        return registry.snapshots().values().stream().map(ExtensionController::toView).toList();
    }

    @GetMapping("/{id}")
    public ExtensionView get(@PathVariable String id) {
        return toView(registry.snapshot(id));
    }

    @PostMapping("/changes")
    public List<ExtensionView> change(@RequestBody ChangeRequestDto request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ExtensionException(ExtensionErrorCode.INVALID_REQUEST,
                    "change set must contain at least one item", Map.of());
        }
        List<StateItem> items = request.items().stream()
                .map((ChangeItemDto dto) -> new StateItem(dto.extensionId(), dto.enabled(), dto.config()))
                .toList();
        registry.applyChange(new StateChangeRequest(items));
        return items.stream()
                .map(item -> toView(registry.snapshot(item.extensionId())))
                .toList();
    }

    @PostMapping("/{id}/execute")
    public ExecuteResponse execute(@PathVariable String id,
                                   @RequestBody(required = false) ExecuteRequest request) {
        Map<String, Object> input = request == null || request.input() == null
                ? Map.of()
                : request.input();
        Map<String, Object> result = registry.execute(id, input);
        return new ExecuteResponse(id, "OK", result);
    }

    private static ExtensionView toView(ExtensionSnapshot snapshot) {
        List<DependencyView> deps = snapshot.definition().dependencies().stream()
                .map(d -> new DependencyView(d.extensionId(), d.acceptedRange().toString()))
                .toList();
        return new ExtensionView(snapshot.definition().id(),
                snapshot.definition().version().toString(),
                snapshot.definition().kind(),
                snapshot.status().name(),
                deps,
                snapshot.effectiveConfig());
    }
}
