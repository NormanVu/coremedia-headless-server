package com.coremedia.caas.server.controller.base;

import com.coremedia.blueprint.base.settings.SettingsService;
import com.coremedia.caas.config.ProcessingDefinition;
import com.coremedia.caas.config.ProcessingDefinitionCacheKey;
import com.coremedia.caas.execution.ExecutionContext;
import com.coremedia.caas.query.QueryDefinition;
import com.coremedia.caas.server.CaasServiceConfig;
import com.coremedia.caas.server.controller.interceptor.QueryExecutionInterceptor;
import com.coremedia.caas.server.service.request.ClientIdentification;
import com.coremedia.caas.service.ServiceRegistry;
import com.coremedia.caas.service.expression.RequestParameterAccessor;
import com.coremedia.caas.service.repository.RootContext;
import com.coremedia.caas.service.security.AccessControlViolation;
import com.coremedia.cache.Cache;

import com.google.common.collect.Lists;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.validation.constraints.NotNull;

public abstract class GraphQLControllerBase extends ControllerBase {

  private static final Logger LOG = LoggerFactory.getLogger(GraphQLControllerBase.class);


  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private Cache cache;

  @Autowired
  private CaasServiceConfig serviceConfig;

  @Autowired
  private ServiceRegistry serviceRegistry;

  @Autowired
  @Qualifier("settingsService")
  private SettingsService settingsService;

  @Autowired
  @Qualifier("staticProcessingDefinitions")
  private Map<String, ProcessingDefinition> staticProcessingDefinitions;

  @Autowired
  @Qualifier("requestParameterAccessor")
  private RequestParameterAccessor requestParameterAccessor;

  @Autowired(required = false)
  private List<QueryExecutionInterceptor> queryInterceptors;


  public GraphQLControllerBase(String timerName) {
    super(timerName);
  }


  private Object runQuery(@NotNull String tenantId, @NotNull String siteId, @NotNull RootContext rootContext, @NotNull ClientIdentification clientIdentification, @NotNull String queryName, @NotNull String viewName, Map<String, Object> requestParameters, ServletWebRequest request) {
    String definitionName = clientIdentification.getDefinitionName();
    // repository defined runtime definition
    ProcessingDefinitionCacheKey processingDefinitionCacheKey = new ProcessingDefinitionCacheKey(rootContext.getSite().getSiteIndicator(), settingsService, applicationContext);
    ProcessingDefinition processingDefinition = cache.get(processingDefinitionCacheKey).get(definitionName);
    // fallback to static definition
    if (processingDefinition == null) {
      processingDefinition = staticProcessingDefinitions.get(definitionName);
    }
    if (processingDefinition == null || processingDefinition == ProcessingDefinition.INVALID) {
      LOG.error("No valid processing definition found for name '{}'", definitionName);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    // check for query existence
    QueryDefinition queryDefinition = processingDefinition.getQueryRegistry().getDefinition(queryName, viewName);
    if (queryDefinition == null) {
      LOG.error("No query '{}#{}' found in processing definition '{}'", queryName, viewName, definitionName);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    // run pre query interceptors
    if (queryInterceptors != null) {
      for (QueryExecutionInterceptor executionInterceptor : queryInterceptors) {
        if (!executionInterceptor.preQuery(tenantId, siteId, clientIdentification, rootContext, processingDefinition, queryDefinition, requestParameters, request)) {
          return null;
        }
      }
    }
    Object target = rootContext.getTarget();
    // resolve specialized query string based on target type
    String query;
    if (target instanceof List) {
      query = queryDefinition.getQuery();
    }
    else {
      query = queryDefinition.getQuery(processingDefinition.getSchemaService().getObjectType(target).getName());
    }
    // create new runtime context for capturing all required runtime services and state
    ExecutionContext context = new ExecutionContext(processingDefinition, serviceRegistry, rootContext);
    // run query
    ExecutionInput executionInput = ExecutionInput.newExecutionInput()
            .query(query)
            .root(target)
            .context(context)
            .variables(requestParameters)
            .build();
    ExecutionResult result = GraphQL.newGraphQL(queryDefinition.getQuerySchema(target))
            .preparsedDocumentProvider(processingDefinition.getQueryRegistry())
            .build()
            .execute(executionInput);
    if (!result.getErrors().isEmpty()) {
      for (GraphQLError error : result.getErrors()) {
        LOG.error("GraphQL execution error: {}", error.toString());
      }
    }
    Object resultData = result.getData();
    // run post query interceptors
    if (queryInterceptors != null) {
      for (QueryExecutionInterceptor executionInterceptor : Lists.reverse(queryInterceptors)) {
        Object transformedData = executionInterceptor.postQuery(resultData, tenantId, siteId, clientIdentification, rootContext, processingDefinition, queryDefinition, requestParameters, request);
        if (transformedData != null) {
          resultData = transformedData;
        }
      }
    }
    // send response with appropriate cache headers
    CacheControl cacheControl;
    if (serviceConfig.isPreview()) {
      cacheControl = CacheControl.noCache();
    }
    else {
      long cacheFor = serviceConfig.getCacheTime();
      // allow individual query override
      String queryCacheFor = queryDefinition.getOption("cacheFor");
      if (queryCacheFor != null) {
        try {
          cacheFor = Long.parseLong(queryCacheFor);
        } catch (NumberFormatException e) {
          // just log a warning and use default
          LOG.warn("Invalid query cache time specified: {}", queryCacheFor);
        }
      }
      long maxAge = getMaxAge(cacheFor);
      cacheControl = CacheControl.maxAge(maxAge, TimeUnit.SECONDS).mustRevalidate();
    }
    return ResponseEntity.ok()
            .cacheControl(cacheControl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(resultData);
  }


  protected Object execute(String tenantId, String siteId, String queryName, String targetId, String viewName, ServletWebRequest request) {
    try {
      RootContext rootContext;
      if (targetId == null) {
        rootContext = resolveRootContext(tenantId, siteId, request);
      }
      else {
        rootContext = resolveRootContext(tenantId, siteId, targetId, request);
      }
      // determine client
      ClientIdentification clientIdentification = resolveClient(rootContext, request);
      String clientId = clientIdentification.getId().toString();
      String definitionName = clientIdentification.getDefinitionName();
      // get query string parameters from accessor instance
      Map<String, Object> requestParameters = requestParameterAccessor.getParamMap();
      // run query
      return execute(() -> runQuery(tenantId, siteId, rootContext, clientIdentification, queryName, viewName, requestParameters, request), "tenant", tenantId, "site", siteId, "client", clientId, "pd", definitionName, "query", queryName, "view", viewName);
    } catch (AccessControlViolation e) {
      return handleError(e, request);
    } catch (ResponseStatusException e) {
      return handleError(e, request);
    } catch (Exception e) {
      return handleError(e, request);
    }
  }
}
