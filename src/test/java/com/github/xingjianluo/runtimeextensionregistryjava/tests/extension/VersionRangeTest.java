package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.VersionRange;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 版本与兼容范围解析/判定的单元测试。 */
class VersionRangeTest {

    private static final Logger log = LoggerFactory.getLogger(VersionRangeTest.class);

    @Test
    void parsesAndOrdersSemanticVersions() {
        Version v123 = Version.parse("1.2.3");
        log.info("input=1.2.3 parsed={} compareTo(1.2.4)={}", v123, v123.compareTo(Version.parse("1.2.4")));
        assertThat(v123).isEqualTo(new Version(1, 2, 3));
        assertThat(v123).isLessThan(Version.parse("1.2.4"));
        assertThat(Version.parse("1.3.0")).isGreaterThan(Version.parse("1.2.9"));
        assertThat(Version.parse("2.0.0")).isGreaterThan(Version.parse("1.99.99"));
    }

    @Test
    void rejectsMalformedVersionsWithReason() {
        for (String bad : new String[]{"", "1.2", "1.2.x", "1..3", "v1.2.3"}) {
            log.info("malformed version input='{}' -> expect IllegalArgumentException", bad);
            String input = bad;
            assertThatThrownBy(() -> Version.parse(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(bad.isEmpty() ? "must not be blank" : input);
        }
    }

    @Test
    void exactRangeMatchesOnlyExactVersion() {
        VersionRange range = VersionRange.parse("1.2.3");
        log.info("range input=1.2.3 exact={}", range.exact());
        assertThat(range.satisfies(Version.parse("1.2.3"))).isTrue();
        assertThat(range.satisfies(Version.parse("1.2.4"))).isFalse();
    }

    @Test
    void halfOpenRangeHonorsBoundaryInclusivity() {
        // 常见的兼容范围：1.x 全系列（含 1.0.0，不含 2.0.0）
        VersionRange range = VersionRange.parse("[1.0.0,2.0.0)");
        log.info("range input='{}' lowerInclusive={} upperInclusive={}",
                range.raw(), range.lowerInclusive(), range.upperInclusive());
        assertThat(range.satisfies(Version.parse("1.0.0"))).isTrue();
        assertThat(range.satisfies(Version.parse("1.9.9"))).isTrue();
        assertThat(range.satisfies(Version.parse("2.0.0"))).isFalse();
        assertThat(range.satisfies(Version.parse("0.9.9"))).isFalse();
    }

    @Test
    void openEndedRangesWork() {
        assertThat(VersionRange.parse("[1.0.0,)").satisfies(Version.parse("99.0.0"))).isTrue();
        assertThat(VersionRange.parse("(,2.0.0]").satisfies(Version.parse("0.0.1"))).isTrue();
        assertThat(VersionRange.parse("(,2.0.0]").satisfies(Version.parse("2.0.1"))).isFalse();
    }

    @Test
    void rejectsMalformedRanges() {
        for (String bad : new String[]{"[1.0.0,2.0.0", "1.0.0,2.0.0)", "[1.0.0-2.0.0)", "[2.0.0,1.0.0]"}) {
            log.info("malformed range input='{}' -> expect rejection", bad);
            String input = bad;
            assertThatThrownBy(() -> VersionRange.parse(input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
