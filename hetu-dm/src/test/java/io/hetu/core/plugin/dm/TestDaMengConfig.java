/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hetu.core.plugin.dm;

import com.google.common.collect.ImmutableMap;
import io.hetu.core.plugin.oracle.OracleConfig;
import io.hetu.core.plugin.oracle.config.RoundingMode;
import io.hetu.core.plugin.oracle.config.UnsupportedTypeHandling;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static org.testng.Assert.assertEquals;

public class TestDaMengConfig
{
    private static final int NUMBER_DEFAULT_SCALE = 2;

    private static final int NUMBER_DEFAULT_SCALE_ASSERT = 0;

    /**
     * testDaMengPropertyMappings
     */
    @Test
    public void testDaMengPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("oracle.number.default-scale", "2")
                .put("oracle.number.rounding-mode", "DOWN")
                .put("oracle.synonyms.enabled", "true")
                .put("unsupported-type.handling-strategy", "CONVERT_TO_VARCHAR")
                .build();

        OracleConfig expected = new OracleConfig()
                .setNumberDefaultScale(NUMBER_DEFAULT_SCALE)
                .setRoundingMode(RoundingMode.DOWN)
                .setSynonymsEnabled(true)
                .setUnsupportedTypeHandling(UnsupportedTypeHandling.CONVERT_TO_VARCHAR);

        assertFullMapping(properties, expected);
    }

    /**
     * testGetFuncions
     */
    @Test
    public void testGetFunctions()
    {
        OracleConfig config = new OracleConfig();
        RoundingMode roundingMode = config.getRoundingMode();
        UnsupportedTypeHandling typeHandling = config.getUnsupportedTypeHandling();
        int defaultScale = config.getNumberDefaultScale();

        assertEquals(defaultScale, NUMBER_DEFAULT_SCALE_ASSERT);
        assertEquals(roundingMode, RoundingMode.UNNECESSARY);
        assertEquals(typeHandling, UnsupportedTypeHandling.FAIL);
    }
}
