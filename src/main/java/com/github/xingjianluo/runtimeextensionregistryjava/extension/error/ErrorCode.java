package com.github.xingjianluo.runtimeextensionregistryjava.extension.error;

/** 可区分的对外错误类型。 */
public enum ErrorCode {
    /** 注册的扩展标识与已存在标识或同批次其他标识重复。 */
    DUPLICATE_EXTENSION_ID,
    /** 依赖声明的版本范围表达式无法解析。 */
    INVALID_VERSION_RANGE,
    /** 声明的依赖在本次变更后的候选集合中不存在。 */
    MISSING_DEPENDENCY,
    /** 依赖存在但其版本不在声明的兼容范围内。 */
    INCOMPATIBLE_DEPENDENCY_VERSION,
    /** 依赖图中存在环。 */
    CYCLIC_DEPENDENCY,
    /** 请求的扩展标识不存在。 */
    EXTENSION_NOT_FOUND,
    /** 扩展已停用。 */
    EXTENSION_DISABLED,
    /** 扩展已启用但其依赖链尚未就绪，请求不得执行。 */
    DEPENDENCY_NOT_READY,
    /** 配置未通过扩展自身的初始化校验。 */
    INVALID_CONFIG,
    /** 扩展初始化抛出了配置校验以外的异常。 */
    INITIALIZATION_FAILED,
    /** 扩展执行抛出异常，已做故障隔离。 */
    EXECUTION_FAILED,
    /** 请求内容不合法。 */
    BAD_REQUEST,
    /** 未归类的内部错误（不向调用方透出堆栈）。 */
    INTERNAL_ERROR
}
