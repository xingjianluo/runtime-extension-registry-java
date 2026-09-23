package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.model.VersionRange;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionRangeTest {

    private static final Logger LOG = LoggerFactory.getLogger(VersionRangeTest.class);

    @Test
    void exactRangeMatchesOnlyExactVersion() {
        VersionRange range = new VersionRange("1.2.0");
        LOG.info("[input range='{}'] decision: exact match only", range);
        assertTrue(range.contains(Version.parse("1.2.0")));
        assertFalse(range.contains(Version.parse("1.2.1")));
    }

    @Test
    void boundedRangeHonorsInclusiveAndExclusiveBounds() {
        VersionRange lowerInclusive = new VersionRange("[1.0.0,2.0.0)");
        LOG.info("[input range='{}'] decision: lower inclusive, upper exclusive", lowerInclusive);
        assertTrue(lowerInclusive.contains(Version.parse("1.0.0")));
        assertTrue(lowerInclusive.contains(Version.parse("1.9.9")));
        assertFalse(lowerInclusive.contains(Version.parse("2.0.0")));
        assertFalse(lowerInclusive.contains(Version.parse("0.9.9")));

        VersionRange exclusiveLower = new VersionRange("(1.0.0,1.5.0]");
        LOG.info("[input range='{}'] decision: lower exclusive, upper inclusive", exclusiveLower);
        assertFalse(exclusiveLower.contains(Version.parse("1.0.0")));
        assertTrue(exclusiveLower.contains(Version.parse("1.5.0")));
    }

    @Test
    void unboundedSidesAreAccepted() {
        VersionRange atLeast = new VersionRange("[1.0.0,)");
        LOG.info("[input range='{}'] decision: no upper bound", atLeast);
        assertTrue(atLeast.contains(Version.parse("99.0.0")));
        assertFalse(atLeast.contains(Version.parse("0.9.9")));

        VersionRange atMost = new VersionRange("(,1.5.0]");
        LOG.info("[input range='{}'] decision: no lower bound", atMost);
        assertTrue(atMost.contains(Version.parse("0.0.1")));
        assertFalse(atMost.contains(Version.parse("1.5.1")));
    }

    @Test
    void malformedRangeIsRejectedWithDistinguishableCode() {
        for (String bad : new String[]{"", "   ", "[1.0.0]", "[2.0.0,1.0.0]", "abc"}) {
            ExtensionException ex = assertThrows(ExtensionException.class,
                    () -> new VersionRange(bad),
                    "input range='" + bad + "' should be rejected");
            LOG.info("[input range='{}'] decision: rejected code={} reason={}",
                    bad, ex.errorCode(), ex.getMessage());
            assertEquals(ExtensionErrorCode.INVALID_VERSION_RANGE, ex.errorCode());
        }
    }

    @Test
    void malformedVersionIsRejectedWithDistinguishableCode() {
        for (String bad : new String[]{"", "1.x", "1.2.3.4"}) {
            ExtensionException ex = assertThrows(ExtensionException.class,
                    () -> Version.parse(bad),
                    "input version='" + bad + "' should be rejected");
            LOG.info("[input version='{}'] decision: rejected code={} reason={}",
                    bad, ex.errorCode(), ex.getMessage());
            assertEquals(ExtensionErrorCode.INVALID_VERSION, ex.errorCode());
        }
    }
}
