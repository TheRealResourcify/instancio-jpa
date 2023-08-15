/*
 * Copyright 2023 - 2023 Moritz Becker.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobecker.instancio.jpa;

import com.mobecker.instancio.jpa.setting.JpaKeys;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import org.instancio.Node;
import org.instancio.generator.Generator;
import org.instancio.generator.GeneratorSpec;
import org.instancio.spi.InstancioServiceProvider;
import org.instancio.spi.ServiceProviderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link InstancioServiceProvider} that auto configures id generators for JPA id attributes
 * that are not generated by the JPA provider.
 *
 * @since 1.0.0
 */
public class InstancioJpaServiceProvider implements InstancioServiceProvider {
    private static final Logger LOG = LoggerFactory.getLogger(InstancioJpaServiceProvider.class);

    private static final List<JpaAttributeGeneratorResolver> JPA_ATTRIBUTE_GENERATOR_RESOLVERS = Arrays.asList(
        // Order matters
        new IdGeneratorResolver(),
        new StringGeneratorResolver()
    );

    private volatile Metamodel metamodel;
    private volatile String[] generatorProviderExclusions;

    @Override
    public void init(ServiceProviderContext context) {
        this.metamodel = context.getSettings().get(JpaKeys.METAMODEL);
        this.generatorProviderExclusions = convertGeneratorProviderExclusions(
            context.getSettings().get(JpaKeys.GENERATOR_PROVIDER_EXCLUSIONS));
    }

    private static String[] convertGeneratorProviderExclusions(@Nullable String rawExclusions) {
        return rawExclusions == null ? new String[0] : Arrays.stream(rawExclusions.split(","))
            .map(String::trim).toArray(String[]::new);
    }

    @Override
    public GeneratorProvider getGeneratorProvider() {
        Map<Node, Generator<?>> contextualGenerators = new HashMap<>();
        return (node, generators) -> {
            Field field = node.getField();
            if (field != null && metamodel != null && !isExcluded(field)) {
                EntityType<?> entityType;
                try {
                    // Actually, we would need sth like node.getParent().getTargetClass() at this point but the
                    // Instancio API currently does not expose this information. The implication of this is that
                    // annotations on JPA id attributes are not read correctly in some scenarios involving
                    // inheritance (see https://github.com/instancio/instancio/pull/528#issuecomment-1496935873).
                    // However, in this specific case it is not a problem because of
                    // org.instancio.jpa.selector.JpaGeneratedIdSelector that reads the annotations correctly.
                    // So when an id attribtue is annotated with @GeneratedValue the JpaGeneratedIdSelector will
                    // pick it up and Instancio.set's the attribute to null in which case this generator provider
                    // will not be invoked. On the other hand, if there is no @GeneratedValue annotation present
                    // this generator provider will kick in.
                    entityType = metamodel.entity(
                        field.getDeclaringClass());
                } catch (IllegalArgumentException e) {
                    LOG.trace(null, e);
                    return null;
                }

                Attribute<?, ?> idAttr = entityType.getAttribute(field.getName());
                for (JpaAttributeGeneratorResolver jpaAttributeGeneratorResolver : JPA_ATTRIBUTE_GENERATOR_RESOLVERS) {
                    GeneratorSpec<?> generator = jpaAttributeGeneratorResolver.getGenerator(
                        node, generators, idAttr, () -> contextualGenerators);
                    if (generator != null) {
                        return generator;
                    }
                }
            }
            return null;
        };
    }

    private boolean isExcluded(Field field) {
        for (String exclusion : generatorProviderExclusions) {
            String[] exclusionParts = exclusion.split("#");
            String className;
            String fieldName;
            if (exclusionParts.length == 1) {
                className = exclusionParts[0];
                fieldName = null;
            } else if (exclusionParts.length == 2) {
                className = exclusionParts[0];
                fieldName = exclusionParts[1];
            } else {
                throw new IllegalStateException(String.format("Cannot parse exclusion '%s'.", exclusion));
            }
            if (className.equals(field.getDeclaringClass().getName())
                && (fieldName == null || fieldName.equals(field.getName()))) {
                return true;
            }
        }
        return false;
    }
}
