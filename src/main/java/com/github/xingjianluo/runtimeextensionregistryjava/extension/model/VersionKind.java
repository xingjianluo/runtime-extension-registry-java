package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

/** 历史版本的产生方式。 */
public enum VersionKind {
    /** 由一次常规变更（ChangeSet）成功提交产生。 */
    CHANGE,
    /** 由一次回退某个历史版本成功提交产生。 */
    ROLLBACK
}
