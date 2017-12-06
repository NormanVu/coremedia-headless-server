package com.coremedia.caas.schema;

import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SchemaService {

  private static final String CONTENT_OBJECTTYPE_SUFFIX = "Impl";


  private List<GraphQLOutputType> types;

  private Map<String, GraphQLInterfaceType> interfaceTypes;
  private Map<String, GraphQLObjectType> objectTypes;

  private Map<ContentType, GraphQLInterfaceType> contentTypeInterfaceMapping = new HashMap<>();
  private Map<ContentType, GraphQLObjectType> contentTypeObjectMapping = new HashMap<>();

  private Map<ContentType, Set<String>> contentTypeInstanceNamesMapping = new HashMap<>();


  public boolean isInstanceOf(Object source, String baseTypeName) {
    if (source instanceof Content) {
      Set<String> instanceNames = contentTypeInstanceNamesMapping.get(((Content) source).getType());
      if (instanceNames != null) {
        return instanceNames.contains(baseTypeName);
      }
    }
    return false;
  }


  public List<GraphQLOutputType> getTypes() {
    return types;
  }


  public GraphQLInterfaceType getInterfaceType(String typeName) {
    return interfaceTypes.get(typeName);
  }

  public GraphQLInterfaceType getInterfaceType(Object source) {
    if (source instanceof Content) {
      GraphQLInterfaceType interfaceType = contentTypeInterfaceMapping.get(((Content) source).getType());
      if (interfaceType != null) {
        return interfaceType;
      }
    }
    throw new RuntimeException("Interface type not resolved: " + source);
  }


  public GraphQLObjectType getObjectType(String typeName) {
    return objectTypes.get(typeName);
  }

  public GraphQLObjectType getObjectType(Object source) {
    if (source instanceof Content) {
      GraphQLObjectType objectType = contentTypeObjectMapping.get(((Content) source).getType());
      if (objectType != null) {
        return objectType;
      }
    }
    throw new RuntimeException("Object type not resolved: " + source);
  }


  void init(List<GraphQLOutputType> types, ContentRepository contentRepository) {
    this.types = types;
    // map type names by object and interfaces
    this.interfaceTypes = types.stream().filter(GraphQLInterfaceType.class::isInstance).map(GraphQLInterfaceType.class::cast).collect(Collectors.toMap(GraphQLInterfaceType::getName, Function.identity()));
    this.objectTypes = types.stream().filter(GraphQLObjectType.class::isInstance).map(GraphQLObjectType.class::cast).collect(Collectors.toMap(GraphQLObjectType::getName, Function.identity()));
    // build content type mapping to object and interface types
    for (ContentType contentType : contentRepository.getContentTypes()) {
      resolveTargetType(contentType, "", interfaceTypes, contentTypeInterfaceMapping);
      resolveTargetType(contentType, CONTENT_OBJECTTYPE_SUFFIX, objectTypes, contentTypeObjectMapping);
    }
    // build content type mapping to all implemented object and interface type names
    for (ContentType contentType : contentRepository.getContentTypes()) {
      resolveImplementedTypes(contentType, contentTypeInstanceNamesMapping);
    }
  }


  private <T> void resolveTargetType(ContentType contentType, String suffix, Map<String, T> definedTypes, Map<ContentType, T> typeMapping) {
    ContentType currentContentType = contentType;
    while (currentContentType != null) {
      T type = definedTypes.get(currentContentType.getName() + suffix);
      if (type != null) {
        typeMapping.put(contentType, type);
        return;
      }
      currentContentType = currentContentType.getParent();
    }
    throw new RuntimeException("Content type must be resolved: " + contentType);
  }


  private void resolveImplementedTypes(ContentType contentType, Map<ContentType, Set<String>> typeMapping) {
    HashSet<String> implementedTypeNames = new HashSet<>();
    ContentType currentContentType = contentType;
    while (currentContentType != null) {
      GraphQLObjectType type = contentTypeObjectMapping.get(currentContentType);
      // add object type name
      implementedTypeNames.add(type.getName());
      // add all implemented interface names
      implementedTypeNames.addAll(type.getInterfaces().stream().map(GraphQLOutputType::getName).collect(Collectors.toList()));
      currentContentType = currentContentType.getParent();
    }
    typeMapping.put(contentType, implementedTypeNames);
  }
}