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
package io.hetu.core.plugin.iceberg;

import com.google.inject.Module;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorContext;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InternalIcebergConnectorFactoryTest
{
    @Test
    public void testCreateConnector()
    {
        // Setup
        final Map<String, String> config = new HashMap<>();
        final ConnectorContext context = null;
        final Module module = null;
        final Optional<HiveMetastore> metastore = Optional.empty();
        final Optional<FileIoProvider> fileIoProvider = Optional.empty();

        // Run the test
        final Connector result = InternalIcebergConnectorFactory.createConnector("catalogName", config, context, module,
                metastore, fileIoProvider);

        // Verify the results
    }
}
