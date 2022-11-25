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
package io.prestosql.spi.expression;

import io.prestosql.spi.type.Type;
import org.mockito.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.assertEquals;

public class FieldDereferenceTest
{
    @Mock
    private Type mockType;
    @Mock
    private ConnectorExpression mockTarget;

    private FieldDereference fieldDereferenceUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        initMocks(this);
        fieldDereferenceUnderTest = new FieldDereference(mockType, mockTarget, 0);
    }

    @Test
    public void testGetChildren() throws Exception
    {
        // Setup
        // Run the test
        final List<? extends ConnectorExpression> result = fieldDereferenceUnderTest.getChildren();

        // Verify the results
    }

    @Test
    public void testToString() throws Exception
    {
        assertEquals("result", fieldDereferenceUnderTest.toString());
    }
}