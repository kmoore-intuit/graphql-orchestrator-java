package com.intuit.graphql.orchestrator.xtext;

import static java.util.Objects.requireNonNull;

import com.intuit.graphql.graphQL.ArgumentsDefinition;
import com.intuit.graphql.graphQL.DirectiveDefinition;
import com.intuit.graphql.graphQL.NamedType;
import com.intuit.graphql.graphQL.ObjectTypeDefinition;
import com.intuit.graphql.graphQL.TypeDefinition;
import com.intuit.graphql.orchestrator.ServiceProvider;
import com.intuit.graphql.orchestrator.schema.Operation;
import com.intuit.graphql.orchestrator.schema.ServiceMetadata;
import com.intuit.graphql.orchestrator.schema.transform.FieldResolverContext;
import com.intuit.graphql.utils.XtextTypeUtils;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import lombok.Getter;
import org.eclipse.xtext.resource.XtextResourceSet;

/**
 * Runtime graph represents the runtime elements required to build the runtime graphql schema. It also contains
 * batchloaders for optimization.
 */
@Getter
public class XtextGraph implements ServiceMetadata {

  private final ServiceProvider serviceProvider;
  private final XtextResourceSet xtextResourceSet;
  private final Map<Operation, ObjectTypeDefinition> operationMap;
  private final Map<FieldContext, DataFetcherContext> codeRegistry;
  private final Map<FieldContext, ArgumentsDefinition> resolverArgumentFields;
  private final Set<DirectiveDefinition> directives;
  private final Map<String, TypeDefinition> types;
  private final List<FieldResolverContext> fieldResolverContexts;

  private final boolean hasInterfaceOrUnion;
  private final boolean hasFieldResolverDefinition;

  //to be computed and cached
  private boolean cacheComputed = false;

  /**
   * Container that holds all Object Type Definitions for a Graph. Input Object Type Definitions are expected to be
   * cached and computed in a separate map.
   */
  private Map<String, ObjectTypeDefinition> objectTypeDefinitions;

  private XtextGraph(Builder builder) {
    serviceProvider = builder.serviceProvider;
    xtextResourceSet = requireNonNull(builder.xtextResourceSet, "Resource Set cannot be null");
    //TODO: Research on all Providers having an XtextResource instead of a ResourceSet
    operationMap = builder.operationMap;
    codeRegistry = builder.codeRegistry;
    directives = builder.directives;
    types = builder.types;
    hasInterfaceOrUnion = builder.hasInterfaceOrUnion;
    hasFieldResolverDefinition = builder.hasFieldResolverDefinition;
    resolverArgumentFields = builder.resolverArgumentFields;
    fieldResolverContexts = builder.fieldResolverContexts;
  }

  /**
   * Creates a new Builder
   *
   * @return the Builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Creates a builder from a copy.
   *
   * @param copy the copy
   * @return the builder
   */
  public static Builder newBuilder(XtextGraph copy) {
    Builder builder = new Builder();
    builder.serviceProvider = copy.serviceProvider;
    builder.xtextResourceSet = copy.xtextResourceSet;
    builder.operationMap = copy.getOperationMap();
    builder.codeRegistry = copy.getCodeRegistry();
    builder.directives = copy.getDirectives();
    builder.types = copy.getTypes();
    builder.hasInterfaceOrUnion = copy.hasInterfaceOrUnion;
    builder.hasFieldResolverDefinition = copy.hasFieldResolverDefinition;
    builder.resolverArgumentFields = copy.resolverArgumentFields;
    builder.fieldResolverContexts = copy.fieldResolverContexts;
    return builder;
  }

  /**
   * Empty runtime graph.
   *
   * @return the runtime graph
   */
  public static XtextGraph emptyGraph() {

    Map<Operation, ObjectTypeDefinition> newMap = new EnumMap<>(Operation.class);
    for (Operation op : Operation.values()) {
      newMap.put(op, op.asObjectTypeDefinition());
    }

    return new Builder()
        .xtextResourceSet(XtextResourceSetBuilder.newBuilder().build())
        .operationMap(newMap)
        .build();
  }

  /**
   * Check if the given provider's schema (contains unions/interfaces and) requires typename to be injected in its
   * queries
   *
   * @return true or false
   */
  @Override
  public boolean requiresTypenameInjection() {
    return isHasInterfaceOrUnion();
  }

  @Override
  public boolean hasFieldResolverDirective() {
    return hasFieldResolverDefinition;
  }

  /**
   * Check if the given typeName exists in provider's schema.
   *
   * @param typeName typeName to check
   * @return true or false
   */
  @Override
  public boolean hasType(String typeName) {
    return types.containsKey(typeName);
  }

  public TypeDefinition getType(final NamedType namedType) {
    return types.get(XtextTypeUtils.typeName(namedType));
  }

  public TypeDefinition getType(final String typeName) {
    return types.get(typeName);
  }

  public void addType(TypeDefinition type) {
    this.types.put(type.getName(), type);
  }

  public TypeDefinition removeType(TypeDefinition type) {
    return this.types.remove(type.getName());
  }

  public Map<String, ObjectTypeDefinition> objectTypeDefinitionsByName() {
    computeAndCacheCollections();

    return objectTypeDefinitions;
  }

  private void computeAndCacheCollections() {
    objectTypeDefinitions = new HashMap<>();
    for (final Entry<String, TypeDefinition> entry : types.entrySet()) {
      String name = entry.getKey();
      TypeDefinition typeDef = entry.getValue();

      if (typeDef instanceof ObjectTypeDefinition) {
        objectTypeDefinitions.put(name, (ObjectTypeDefinition) typeDef);
      }
    }

    ObjectTypeDefinition queryType = operationMap.get(Operation.QUERY);
    objectTypeDefinitions.put(queryType.getName(), queryType);

    cacheComputed = true;
  }

  /**
   * Gets operation.
   *
   * @param operation the operation
   * @return the operation
   */
  public ObjectTypeDefinition getOperationType(Operation operation) {
    return operationMap.get(operation);
  }

  public Operation getOperation(String typename) {
    return operationMap.entrySet()
        .stream()
        .filter(entry -> entry.getValue().getName().equals(typename))
        .map(Map.Entry::getKey)
        .findAny().orElse(null);
  }

  public boolean isOperationType(TypeDefinition typeDefinition) {
    return operationMap.containsValue(typeDefinition);
  }

  /**
   * Transform runtime graph.
   *
   * @param builderConsumer the builder consumer
   * @return the runtime graph
   */
  public XtextGraph transform(Consumer<Builder> builderConsumer) {
    Builder builder = newBuilder(this);
    builderConsumer.accept(builder);
    return builder.build();
  }

  /**
   * The type Builder.
   */
  public static final class Builder {

    private ServiceProvider serviceProvider = null;
    private XtextResourceSet xtextResourceSet = null;
    private Map<Operation, ObjectTypeDefinition> operationMap = new HashMap<>();
    private Map<FieldContext, DataFetcherContext> codeRegistry = new HashMap<>();
    private Map<FieldContext, ArgumentsDefinition> resolverArgumentFields = new HashMap<>();
    private Set<DirectiveDefinition> directives = new HashSet<>();
    private Map<String, TypeDefinition> types = new HashMap<>();
    private List<FieldResolverContext> fieldResolverContexts = new ArrayList<>();
    private boolean hasInterfaceOrUnion = false;
    private boolean hasFieldResolverDefinition = false;

    private Builder() {
    }

    /**
     * Operation map builder.
     *
     * @param operationMap the operation map
     * @return the builder
     */
    public Builder operationMap(Map<Operation, ObjectTypeDefinition> operationMap) {
      this.operationMap.putAll(operationMap);
      return this;
    }

    /**
     * Object Types
     *
     * @param codeRegistry the codeRegistry map
     * @return the builder
     */
    public Builder codeRegistry(Map<FieldContext, DataFetcherContext> codeRegistry) {
      this.codeRegistry.putAll(codeRegistry);
      return this;
    }

    public Builder dataFetcherContext(FieldContext fieldCoordinate, DataFetcherContext dataFetcherContext) {
      this.codeRegistry.put(requireNonNull(fieldCoordinate), requireNonNull(dataFetcherContext));
      return this;
    }

    /**
     * Query builder.
     *
     * @param query the query
     * @return the builder
     */
    public Builder query(ObjectTypeDefinition query) {
      this.operationMap.put(Operation.QUERY, requireNonNull(query));
      return this;
    }

    /**
     * Mutation builder.
     *
     * @param mutation the mutation
     * @return the builder
     */
    public Builder mutation(ObjectTypeDefinition mutation) {
      this.operationMap.put(Operation.MUTATION, requireNonNull(mutation));
      return this;
    }

    /**
     * Service Context builder.
     *
     * @param serviceProvider the service provider
     * @return the builder
     */
    public Builder serviceProvider(ServiceProvider serviceProvider) {
      requireNonNull(serviceProvider);
      this.serviceProvider = serviceProvider;
      return this;
    }

    /**
     * XtextResourceSet builder.
     *
     * @param xtextResourceSet the resource set
     * @return the builder
     */
    public Builder xtextResourceSet(XtextResourceSet xtextResourceSet) {
      requireNonNull(xtextResourceSet);
      this.xtextResourceSet = xtextResourceSet;
      return this;
    }

    /**
     * Directives builder.
     *
     * @param directiveDefinitions the set of directives
     * @return the builder
     */
    public Builder directives(Set<DirectiveDefinition> directiveDefinitions) {
      requireNonNull(directiveDefinitions);
      this.directives.addAll(directiveDefinitions);
      return this;
    }

    /**
     * Types builder.
     *
     * @param types the map of xtext graphql types
     * @return the builder
     */
    public Builder types(Map<String, TypeDefinition> types) {
      requireNonNull(types);
      this.types.putAll(types);
      return this;
    }

    /**
     * isHasInterfaceOrUnion builder.
     *
     * @param hasInterfaceOrUnion boolean value
     * @return the builder
     */
    public Builder hasInterfaceOrUnion(boolean hasInterfaceOrUnion) {
      this.hasInterfaceOrUnion = hasInterfaceOrUnion;
      return this;
    }

    /**
     * hasFieldResolverDefinition builder.
     *
     * @param hasFieldResolverDefinition boolean value
     * @return the builder
     */
    public Builder hasFieldResolverDefinition(boolean hasFieldResolverDefinition) {
      this.hasFieldResolverDefinition = hasFieldResolverDefinition;
      return this;
    }

    public Builder fieldResolverContexts(List<FieldResolverContext> fieldResolverContexts) {
      this.fieldResolverContexts.addAll(fieldResolverContexts);
      return this;
    }

    public Builder clearFieldResolverContexts() {
      this.fieldResolverContexts.clear();
      return this;
    }

    /**
     * Build runtime graph.
     *
     * @return the runtime graph
     */
    public XtextGraph build() {
      return new XtextGraph(this);
    }
  }
}
