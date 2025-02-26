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

import static com.mobecker.instancio.jpa.util.JpaMetamodelUtil.getAnnotation;
import static com.mobecker.instancio.jpa.util.JpaMetamodelUtil.resolveIdAttribute;

import com.mobecker.instancio.jpa.setting.JpaKeys;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.persistence.GeneratedValue;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;
import org.instancio.Node;
import org.instancio.generator.Generator;
import org.instancio.internal.generator.sequence.IntegerSequenceGenerator;
import org.instancio.internal.generator.sequence.LongSequenceGenerator;
import org.instancio.spi.InstancioServiceProvider;
import org.instancio.spi.ServiceProviderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstancioJpaServiceProvider implements InstancioServiceProvider {
    private static final Logger LOG = LoggerFactory.getLogger(InstancioJpaServiceProvider.class);

    private static final Map<Class<?>, Class<? extends Generator<?>>> ID_GENERATORS;

    static {
        Map<Class<?>, Class<? extends Generator<?>>> idGenerators = new HashMap<>(1);
        idGenerators.put(Long.class, LongSequenceGenerator.class);
        idGenerators.put(Integer.class, IntegerSequenceGenerator.class);
        ID_GENERATORS = Collections.unmodifiableMap(idGenerators);
    }

    private Metamodel metamodel;

    @Override
    public void init(ServiceProviderContext context) {
        this.metamodel = context.getSettings().get(JpaKeys.METAMODEL);
    }

    @Override
    public GeneratorProvider getGeneratorProvider() {
        Map<Node, Generator<?>> contextualGenerators = new HashMap<>();
        return (node, generators) -> {
            Field field = node.getField();
            if (field != null && metamodel != null) {
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
                SingularAttribute<?, ?> idAttr = resolveIdAttribute(entityType, field.getName());
                if (idAttr != null && getAnnotation(idAttr, GeneratedValue.class) == null) {
                    return resolveIdGenerator(contextualGenerators, node, idAttr.getJavaType());
                }
            }
            return null;
        };
    }

    private Generator<?> resolveIdGenerator(
        Map<Node, Generator<?>> contextualGenerators, Node node, Class<?> idClass
    ) {
        Generator<?> generator = contextualGenerators.get(node);
        if (generator != null) {
            return generator;
        }
        generator = instantiateIdGenerator(idClass);
        if (generator != null) {
            contextualGenerators.put(node, generator);
        }
        return generator;
    }

    private static Generator<?> instantiateIdGenerator(Class<?> idClass) {
        Class<?> generatorClass = ID_GENERATORS.get(idClass);
        if (generatorClass != null) {
            try {
                Constructor<?> constructor = generatorClass.getConstructor();
                return (Generator<?>) constructor.newInstance();
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                     | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }
}
