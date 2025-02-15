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
package io.prestosql.operator.scalar;

import com.google.common.annotations.VisibleForTesting;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionMetadata;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.operator.ParametricImplementationsGroup;
import io.prestosql.operator.scalar.annotations.ParametricScalarImplementation;
import io.prestosql.spi.PrestoException;

import java.util.Optional;

import static io.prestosql.metadata.SignatureBinder.applyBoundVariables;
import static io.prestosql.spi.StandardErrorCode.AMBIGUOUS_FUNCTION_IMPLEMENTATION;
import static io.prestosql.spi.StandardErrorCode.FUNCTION_IMPLEMENTATION_ERROR;
import static io.prestosql.spi.StandardErrorCode.FUNCTION_IMPLEMENTATION_MISSING;
import static io.prestosql.util.Failures.checkCondition;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class ParametricScalar
        extends SqlScalarFunction
{
    private final ScalarHeader details;
    private final ParametricImplementationsGroup<ParametricScalarImplementation> implementations;

    public ParametricScalar(
            Signature signature,
            ScalarHeader details,
            ParametricImplementationsGroup<ParametricScalarImplementation> implementations)
    {
        super(new FunctionMetadata(
                signature,
                details.isHidden(),
                details.isDeterministic(),
                details.getDescription().orElse("")));
        this.details = requireNonNull(details);
        this.implementations = requireNonNull(implementations);
    }

    @VisibleForTesting
    public ParametricImplementationsGroup<ParametricScalarImplementation> getImplementations()
    {
        return implementations;
    }

    @Override
    public ScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, Metadata metadata)
    {
        Signature boundSignature = applyBoundVariables(getFunctionMetadata().getSignature(), boundVariables, arity);
        if (implementations.getExactImplementations().containsKey(boundSignature)) {
            ParametricScalarImplementation implementation = implementations.getExactImplementations().get(boundSignature);
            Optional<ScalarFunctionImplementation> scalarFunctionImplementation = implementation.specialize(boundSignature, boundVariables, metadata);
            checkCondition(scalarFunctionImplementation.isPresent(), FUNCTION_IMPLEMENTATION_ERROR, format("Exact implementation of %s do not match expected java types.", boundSignature.getName()));
            return scalarFunctionImplementation.get();
        }

        ScalarFunctionImplementation selectedImplementation = null;
        for (ParametricScalarImplementation implementation : implementations.getSpecializedImplementations()) {
            Optional<ScalarFunctionImplementation> scalarFunctionImplementation = implementation.specialize(boundSignature, boundVariables, metadata);
            if (scalarFunctionImplementation.isPresent()) {
                checkCondition(selectedImplementation == null, AMBIGUOUS_FUNCTION_IMPLEMENTATION, "Ambiguous implementation for %s with bindings %s", getFunctionMetadata().getSignature(), boundVariables.getTypeVariables());
                selectedImplementation = scalarFunctionImplementation.get();
            }
        }
        if (selectedImplementation != null) {
            return selectedImplementation;
        }
        for (ParametricScalarImplementation implementation : implementations.getGenericImplementations()) {
            Optional<ScalarFunctionImplementation> scalarFunctionImplementation = implementation.specialize(boundSignature, boundVariables, metadata);
            if (scalarFunctionImplementation.isPresent()) {
                checkCondition(selectedImplementation == null, AMBIGUOUS_FUNCTION_IMPLEMENTATION, "Ambiguous implementation for %s with bindings %s", getFunctionMetadata().getSignature(), boundVariables.getTypeVariables());
                selectedImplementation = scalarFunctionImplementation.get();
            }
        }
        if (selectedImplementation != null) {
            return selectedImplementation;
        }

        throw new PrestoException(FUNCTION_IMPLEMENTATION_MISSING, format("Unsupported type parameters (%s) for %s", boundVariables, getFunctionMetadata().getSignature()));
    }
}
