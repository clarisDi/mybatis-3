/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.annotation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.FetchType;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {

  /**
   * SQL 操作注解集合
   */
  private static final Set<Class<? extends Annotation>> statementAnnotationTypes = Stream
      .of(Select.class, Update.class, Insert.class, Delete.class, SelectProvider.class, UpdateProvider.class,
          InsertProvider.class, DeleteProvider.class)
      .collect(Collectors.toSet());

  private final Configuration configuration;
  private final MapperBuilderAssistant assistant;
  /**
   * Mapper 接口类
   */
  private final Class<?> type;

  public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
    // 创建 MapperBuilderAssistant 对象
    String resource = type.getName().replace('.', '/') + ".java (best guess)";
    this.assistant = new MapperBuilderAssistant(configuration, resource);
    this.configuration = configuration;
    this.type = type;
  }

  public void parse() {
    // 判断当前 Mapper 接口是否应加载过。
    String resource = type.toString();
    if (!configuration.isResourceLoaded(resource)) {
      //加载对应的XML Mapper
      loadXmlResource();
      // 标记该 Mapper 接口已经加载过
      configuration.addLoadedResource(resource);
      // 设置 namespace 属性
      assistant.setCurrentNamespace(type.getName());
      //解析 @CacheNamespace 注解
      parseCache();
      //解析 @CacheNamespaceRef 注解
      parseCacheRef();
      // 遍历每个方法，解析其上的注解
      for (Method method : type.getMethods()) {
        //不是桥接方法并且不是default修饰的方法，才不满足if判断
        if (!canHaveStatement(method)) {
          continue;
        }
        if (getAnnotationWrapper(method, false, Select.class, SelectProvider.class).isPresent()
            && method.getAnnotation(ResultMap.class) == null) {
          parseResultMap(method);
        }
        try {
          // 执行解析
          parseStatement(method);
        } catch (IncompleteElementException e) {
          // 解析失败，添加到 configuration 中
          configuration.addIncompleteMethod(new MethodResolver(this, method));
        }
      }
    }
    // 解析待定的方法
    parsePendingMethods();
  }

  private boolean canHaveStatement(Method method) {
    // issue #237
    return !method.isBridge() && !method.isDefault();
  }

  private void parsePendingMethods() {
    // 获得 MethodResolver 集合，并遍历进行处理
    Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
    synchronized (incompleteMethods) {
      Iterator<MethodResolver> iter = incompleteMethods.iterator();
      while (iter.hasNext()) {
        try {
          // 执行解析
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // This method is still missing a resource
        }
      }
    }
  }

  private void loadXmlResource() {
    // Spring may not know the real resource name so we check a flag
    // to prevent loading again a resource twice
    // this flag is set at XMLMapperBuilder#bindMapperForNamespace
    //判断Mapper XML是否已经加载过，如果加载过，就不加载了。
    // 此处，是为了避免和 XMLMapperBuilder#parse() 方法冲突，重复解析
    if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
      String xmlResource = type.getName().replace('.', '/') + ".xml";
      // #1347
      // 获得 InputStream 对象
      InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
      if (inputStream == null) {
        // Search XML mapper that is not in the module but in the classpath.
        try {
          inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
        } catch (IOException e2) {
          // ignore, resource is not required
        }
      }
      //创建 XMLMapperBuilder 对象，执行解析
      if (inputStream != null) {
        XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
        xmlParser.parse();
      }
    }
  }

  private void parseCache() {
    //获得类上的 @CacheNamespace 注解
    CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
    if (cacheDomain != null) {
      //获得各种属性
      Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
      Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
      //获得 Properties 属性
      Properties props = convertToProperties(cacheDomain.properties());
      //创建 Cache 对象
      assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
    }
  }

  private Properties convertToProperties(Property[] properties) {
    if (properties.length == 0) {
      return null;
    }
    Properties props = new Properties();
    for (Property property : properties) {
      // 替换
      props.setProperty(property.name(),
          PropertyParser.parse(property.value(), configuration.getVariables()));
    }
    return props;
  }

  private void parseCacheRef() {
    // 获得类上的 @CacheNamespaceRef 注解
    CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
    if (cacheDomainRef != null) {
      // 获得各种属性
      Class<?> refType = cacheDomainRef.value();
      String refName = cacheDomainRef.name();
      // 校验，如果 refType 和 refName 都为空，则抛出 BuilderException 异常
      if (refType == void.class && refName.isEmpty()) {
        throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
      }
      //校验，如果 refType 和 refName 都不为空，则抛出 BuilderException 异常
      if (refType != void.class && !refName.isEmpty()) {
        throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
      }
      // 获得最终的 namespace 属性
      String namespace = (refType != void.class) ? refType.getName() : refName;
      try {
        // 获得指向的 Cache 对象
        assistant.useCacheRef(namespace);
      } catch (IncompleteElementException e) {
        configuration.addIncompleteCacheRef(new CacheRefResolver(assistant, namespace));
      }
    }
  }

  private String parseResultMap(Method method) {
    // 获得返回类型
    Class<?> returnType = getReturnType(method);
    // 获得 @ConstructorArgs、@Results、@TypeDiscriminator 注解
    Arg[] args = method.getAnnotationsByType(Arg.class);
    Result[] results = method.getAnnotationsByType(Result.class);
    TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
    // 生成 resultMapId
    String resultMapId = generateResultMapName(method);
    // 生成 ResultMap 对象
    applyResultMap(resultMapId, returnType, args, results, typeDiscriminator);
    return resultMapId;
  }

  private String generateResultMapName(Method method) {
    // 第一种情况，已经声明
    // 如果有 @Results 注解，并且有设置 id 属性，则直接返回。格式为：`${type.name}.${Results.id}` 。
    Results results = method.getAnnotation(Results.class);
    if (results != null && !results.id().isEmpty()) {
      return type.getName() + "." + results.id();
    }
    // 第二种情况，自动生成
    // 获得 suffix 前缀，相当于方法参数构成的签名
    StringBuilder suffix = new StringBuilder();
    for (Class<?> c : method.getParameterTypes()) {
      suffix.append("-");
      suffix.append(c.getSimpleName());
    }
    if (suffix.length() < 1) {
      suffix.append("-void");
    }
    // 拼接返回。格式为 `${type.name}.${method.name}${suffix}` 。
    return type.getName() + "." + method.getName() + suffix;
  }

  private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
    // 创建 ResultMapping 数组
    List<ResultMapping> resultMappings = new ArrayList<>();
    // 将 @Arg[] 注解数组，解析成对应的 ResultMapping 对象们，并添加到 resultMappings 中。
    applyConstructorArgs(args, returnType, resultMappings);
    // 将 @Result[] 注解数组，解析成对应的 ResultMapping 对象们，并添加到 resultMappings 中。
    applyResults(results, returnType, resultMappings);
    // 创建 Discriminator 对象
    Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
    // TODO add AutoMappingBehaviour
    // ResultMap 对象
    assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
    // 创建 Discriminator 的 ResultMap 对象们
    createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
  }

  private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      for (Case c : discriminator.cases()) {
        String caseResultMapId = resultMapId + "-" + c.value();
        List<ResultMapping> resultMappings = new ArrayList<>();
        // issue #136
        applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
        applyResults(c.results(), resultType, resultMappings);
        // TODO add AutoMappingBehaviour
        assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
      }
    }
  }

  private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    // 解析各种属性
    if (discriminator != null) {
      String column = discriminator.column();
      Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
      JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
      @SuppressWarnings("unchecked")
      // 获得 TypeHandler 类
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
      // 遍历 @Case[] 注解数组，解析成 discriminatorMap 集合
      Case[] cases = discriminator.cases();
      Map<String, String> discriminatorMap = new HashMap<>();
      for (Case c : cases) {
        String value = c.value();
        String caseResultMapId = resultMapId + "-" + value;
        discriminatorMap.put(value, caseResultMapId);
      }
      // 创建 Discriminator 对象
      return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
    }
    return null;
  }

  void parseStatement(Method method) {
    //获得参数的类型
    final Class<?> parameterTypeClass = getParameterType(method);
    //获得 LanguageDriver 对象
    final LanguageDriver languageDriver = getLanguageDriver(method);

    getAnnotationWrapper(method, true, statementAnnotationTypes).ifPresent(statementAnnotation -> {
      final SqlSource sqlSource = buildSqlSource(statementAnnotation.getAnnotation(), parameterTypeClass, languageDriver, method);
      final SqlCommandType sqlCommandType = statementAnnotation.getSqlCommandType();
      final Options options = getAnnotationWrapper(method, false, Options.class).map(x -> (Options)x.getAnnotation()).orElse(null);
      final String mappedStatementId = type.getName() + "." + method.getName();

      // 获得 KeyGenerator 对象
      final KeyGenerator keyGenerator;
      String keyProperty = null;
      String keyColumn = null;
      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
        // first check for SelectKey annotation - that overrides everything else
        SelectKey selectKey = getAnnotationWrapper(method, false, SelectKey.class).map(x -> (SelectKey)x.getAnnotation()).orElse(null);
        //如果有 @SelectKey 注解，则进行处理
        if (selectKey != null) {
          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
          keyProperty = selectKey.keyProperty();
        } else if (options == null) { //如果无 @Options 注解，则根据全局配置处理
          keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        } else { // 如果有 @Options 注解，则使用该注解的配置处理
          keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
          keyProperty = options.keyProperty();
          keyColumn = options.keyColumn();
        }
      } else {
        keyGenerator = NoKeyGenerator.INSTANCE;
      }

      Integer fetchSize = null;
      Integer timeout = null;
      StatementType statementType = StatementType.PREPARED;
      ResultSetType resultSetType = configuration.getDefaultResultSetType();
      boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
      boolean flushCache = !isSelect;
      boolean useCache = isSelect;
      if (options != null) {
        if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
          flushCache = true;
        } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
          flushCache = false;
        }
        useCache = options.useCache();
        fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null; //issue #348
        timeout = options.timeout() > -1 ? options.timeout() : null;
        statementType = options.statementType();
        if (options.resultSetType() != ResultSetType.DEFAULT) {
          resultSetType = options.resultSetType();
        }
      }
      // 获得 resultMapId 编号字符串
      String resultMapId = null;
      if (isSelect) {
        // 如果有 @ResultMap 注解，使用该注解为 resultMapId 属性
        ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
        if (resultMapAnnotation != null) {
          resultMapId = String.join(",", resultMapAnnotation.value());
        } else {  //如果无 @ResultMap 注解，解析其它注解，作为 resultMapId 属性
          resultMapId = generateResultMapName(method);
        }
      }

      // 构建 MappedStatement 对象
      assistant.addMappedStatement(
          mappedStatementId,
          sqlSource,
          statementType,
          sqlCommandType,
          fetchSize,
          timeout,
          // ParameterMapID
          null,
          parameterTypeClass,
          resultMapId,
          getReturnType(method), // 获得返回类型
          resultSetType,
          flushCache,
          useCache,
          // TODO gcode issue #577
          false,
          keyGenerator,
          keyProperty,
          keyColumn,
          statementAnnotation.getDatabaseId(),
          languageDriver,
          // ResultSets
          options != null ? nullOrEmpty(options.resultSets()) : null);
    });
  }

  private LanguageDriver getLanguageDriver(Method method) {
    // 解析 @Lang 注解，获得对应的类型
    Lang lang = method.getAnnotation(Lang.class);
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = lang.value();
    }
    // 获得 LanguageDriver 对象
    // 如果 langClass 为空，即无 @Lang 注解，则会使用默认 LanguageDriver 类型
    return configuration.getLanguageDriver(langClass);
  }

  private Class<?> getParameterType(Method method) {
    Class<?> parameterType = null;
    // 遍历参数类型数组
    // 排除 RowBounds 和 ResultHandler 两种参数
    // 1. 如果是多参数，则是 ParamMap 类型
    // 2. 如果是单参数，则是该参数的类型
    Class<?>[] parameterTypes = method.getParameterTypes();
    for (Class<?> currentParameterType : parameterTypes) {
      if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class.isAssignableFrom(currentParameterType)) {
        if (parameterType == null) {
          parameterType = currentParameterType;
        } else {
          // issue #135
          parameterType = ParamMap.class;
        }
      }
    }
    return parameterType;
  }

  private Class<?> getReturnType(Method method) {
    // 获得方法的返回类型
    Class<?> returnType = method.getReturnType();
    // 解析成对应的 Type
    Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
    // 如果 Type 是 Class ，普通类
    if (resolvedReturnType instanceof Class) {
      returnType = (Class<?>) resolvedReturnType;
      // 如果是数组类型，则使用 componentType
      if (returnType.isArray()) {
        returnType = returnType.getComponentType();
      }
      // gcode issue #508
      // 如果返回类型是 void ，则尝试使用 @ResultType 注解
      if (void.class.equals(returnType)) {
        ResultType rt = method.getAnnotation(ResultType.class);
        if (rt != null) {
          returnType = rt.value();
        }
      }
      // 如果 Type 是 ParameterizedType ，泛型
    } else if (resolvedReturnType instanceof ParameterizedType) {
      // 获得泛型 rawType
      ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
      Class<?> rawType = (Class<?>) parameterizedType.getRawType();
      // 如果是 Collection 或者 Cursor 类型时
      if (Collection.class.isAssignableFrom(rawType) || Cursor.class.isAssignableFrom(rawType)) {
        // 获得 <> 中实际类型
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        // 如果 actualTypeArguments 的大小为 1 ，进一步处理
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          Type returnTypeParameter = actualTypeArguments[0];
          // 如果是 Class ，则直接使用 Class
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
            // 如果是 ParameterizedType ，则获取 <> 中实际类型
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue #443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
            // 如果是泛型数组类型，则获得 genericComponentType 对应的类
          } else if (returnTypeParameter instanceof GenericArrayType) {
            Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
            // (gcode issue #525) support List<byte[]>
            returnType = Array.newInstance(componentType, 0).getClass();
          }
        }
        // 如果有 @MapKey 注解，并且是 Map 类型
      } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
        // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
        // 获得 <> 中实际类型
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        // 为什么是 2 ，因为 Map<K, V> 呀，有 K、V 两个泛型
        if (actualTypeArguments != null && actualTypeArguments.length == 2) {
          // 处理 V 泛型
          Type returnTypeParameter = actualTypeArguments[1];
          // 如果 V 泛型为 Class ，则直接使用 Class
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
            // 如果 V 泛型为 ParameterizedType ，则获取 <> 中实际类型
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue 443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          }
        }
        // 如果是 Optional 类型时
      } else if (Optional.class.equals(rawType)) {
        // 获得 <> 中实际类型
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        // 因为是 Optional<T> 类型，所以 actualTypeArguments 数组大小是一
        Type returnTypeParameter = actualTypeArguments[0];
        // 如果 <T> 泛型为 Class ，则直接使用 Class
        if (returnTypeParameter instanceof Class<?>) {
          returnType = (Class<?>) returnTypeParameter;
        }
      }
    }

    return returnType;
  }

  private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
    // 遍历 @Result[] 数组
    for (Result result : results) {
      // 创建 ResultFlag 数组
      List<ResultFlag> flags = new ArrayList<>();
      if (result.id()) {
        flags.add(ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      // 获得 TypeHandler 类
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              ((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());
      boolean hasNestedResultMap = hasNestedResultMap(result);
      // 构建 ResultMapping 对象
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(result.property()),
          nullOrEmpty(result.column()),
          result.javaType() == void.class ? null : result.javaType(),
          result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
          hasNestedSelect(result) ? nestedSelectId(result) : null,
          hasNestedResultMap ? nestedResultMapId(result) : null,
          null,
          hasNestedResultMap ? findColumnPrefix(result) : null,
          typeHandler,
          flags,
          null,
          null,
          isLazy(result));
      // 添加到 resultMappings 中
      resultMappings.add(resultMapping);
    }
  }

  private String findColumnPrefix(Result result) {
    String columnPrefix = result.one().columnPrefix();
    if (columnPrefix.length() < 1) {
      columnPrefix = result.many().columnPrefix();
    }
    return columnPrefix;
  }

  private String nestedResultMapId(Result result) {
    String resultMapId = result.one().resultMap();
    if (resultMapId.length() < 1) {
      resultMapId = result.many().resultMap();
    }
    if (!resultMapId.contains(".")) {
      resultMapId = type.getName() + "." + resultMapId;
    }
    return resultMapId;
  }

  private boolean hasNestedResultMap(Result result) {
    if (result.one().resultMap().length() > 0 && result.many().resultMap().length() > 0) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    return result.one().resultMap().length() > 0 || result.many().resultMap().length() > 0;
  }

  private String nestedSelectId(Result result) {
    // 先获得 @One 注解
    String nestedSelect = result.one().select();
    if (nestedSelect.length() < 1) {
      // 获得不到，则再获得 @Many
      nestedSelect = result.many().select();
    }
    // 获得内嵌查询编号，格式为 `{type.name}.${select}`
    if (!nestedSelect.contains(".")) {
      nestedSelect = type.getName() + "." + nestedSelect;
    }
    return nestedSelect;
  }

  private boolean isLazy(Result result) {
    // 判断是否开启懒加载
    boolean isLazy = configuration.isLazyLoadingEnabled();
    // 如果有 @One 注解，则判断是否懒加载
    if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
      isLazy = result.one().fetchType() == FetchType.LAZY;
      // 如果有 @Many 注解，则判断是否懒加载
    } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
      isLazy = result.many().fetchType() == FetchType.LAZY;
    }
    return isLazy;
  }

  private boolean hasNestedSelect(Result result) {
    if (result.one().select().length() > 0 && result.many().select().length() > 0) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    // 判断有 @One 或 @Many 注解
    return result.one().select().length() > 0 || result.many().select().length() > 0;
  }

  private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
    // 遍历 @Arg[] 数组
    for (Arg arg : args) {
      // 创建 ResultFlag 数组
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if (arg.id()) {
        flags.add(ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      // 获得 TypeHandler
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
      // 将当前 @Arg 注解构建成 ResultMapping 对象
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(arg.name()),
          nullOrEmpty(arg.column()),
          arg.javaType() == void.class ? null : arg.javaType(),
          arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
          nullOrEmpty(arg.select()),
          nullOrEmpty(arg.resultMap()),
          null,
          nullOrEmpty(arg.columnPrefix()),
          typeHandler,
          flags,
          null,
          null,
          false);
      // 添加到 resultMappings 中
      resultMappings.add(resultMapping);
    }
  }

  private String nullOrEmpty(String value) {
    return value == null || value.trim().length() == 0 ? null : value;
  }

  private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    // 获得各种属性和对应的类
    String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    Class<?> resultTypeClass = selectKeyAnnotation.resultType();
    StatementType statementType = selectKeyAnnotation.statementType();
    String keyProperty = selectKeyAnnotation.keyProperty();
    String keyColumn = selectKeyAnnotation.keyColumn();
    boolean executeBefore = selectKeyAnnotation.before();

    // defaults
    // 创建 MappedStatement 需要用到的默认值
    boolean useCache = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;
    String databaseId = selectKeyAnnotation.databaseId().isEmpty() ? null : selectKeyAnnotation.databaseId();

    // 创建 SqlSource 对象
    SqlSource sqlSource = buildSqlSource(selectKeyAnnotation, parameterTypeClass, languageDriver, null);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;
    // 创建 MappedStatement 对象
    assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
        flushCache, useCache, false,
        keyGenerator, keyProperty, keyColumn, databaseId, languageDriver, null);
    // 获得 SelectKeyGenerator 的编号，格式为 `${namespace}.${id}`
    id = assistant.applyCurrentNamespace(id, false);
    // 获得 MappedStatement 对象
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    // 创建 SelectKeyGenerator 对象，并添加到 configuration 中
    SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
    configuration.addKeyGenerator(id, answer);
    return answer;
  }

  private SqlSource buildSqlSource(Annotation annotation, Class<?> parameterType, LanguageDriver languageDriver,
      Method method) {
    if (annotation instanceof Select) {
      return buildSqlSourceFromStrings(((Select) annotation).value(), parameterType, languageDriver);
    } else if (annotation instanceof Update) {
      return buildSqlSourceFromStrings(((Update) annotation).value(), parameterType, languageDriver);
    } else if (annotation instanceof Insert) {
      return buildSqlSourceFromStrings(((Insert) annotation).value(), parameterType, languageDriver);
    } else if (annotation instanceof Delete) {
      return buildSqlSourceFromStrings(((Delete) annotation).value(), parameterType, languageDriver);
    } else if (annotation instanceof SelectKey) {
      return buildSqlSourceFromStrings(((SelectKey) annotation).statement(), parameterType, languageDriver);
    }
    return new ProviderSqlSource(assistant.getConfiguration(), annotation, type, method);
  }

  private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass,
      LanguageDriver languageDriver) {
    //创建 SqlSource 对象
    return languageDriver.createSqlSource(configuration, String.join(" ", strings).trim(), parameterTypeClass);
  }

  @SafeVarargs
  private final Optional<AnnotationWrapper> getAnnotationWrapper(Method method, boolean errorIfNoMatch,
      Class<? extends Annotation>... targetTypes) {
    return getAnnotationWrapper(method, errorIfNoMatch, Arrays.asList(targetTypes));
  }

  private Optional<AnnotationWrapper> getAnnotationWrapper(Method method, boolean errorIfNoMatch,
      Collection<Class<? extends Annotation>> targetTypes) {
    //获取数据库标识
    String databaseId = configuration.getDatabaseId();
    Map<String, AnnotationWrapper> statementAnnotations = targetTypes.stream()
        .flatMap(x -> Arrays.stream(method.getAnnotationsByType(x))).map(AnnotationWrapper::new)
        .collect(Collectors.toMap(AnnotationWrapper::getDatabaseId, x -> x, (existing, duplicate) -> {
          throw new BuilderException(String.format("Detected conflicting annotations '%s' and '%s' on '%s'.",
              existing.getAnnotation(), duplicate.getAnnotation(),
              method.getDeclaringClass().getName() + "." + method.getName()));
        }));
    AnnotationWrapper annotationWrapper = null;
    if (databaseId != null) {
      annotationWrapper = statementAnnotations.get(databaseId);
    }
    if (annotationWrapper == null) {
      annotationWrapper = statementAnnotations.get("");
    }
    if (errorIfNoMatch && annotationWrapper == null && !statementAnnotations.isEmpty()) {
      // Annotations exist, but there is no matching one for the specified databaseId
      throw new BuilderException(
          String.format(
              "Could not find a statement annotation that correspond a current database or default statement on method '%s.%s'. Current database id is [%s].",
              method.getDeclaringClass().getName(), method.getName(), databaseId));
    }
    return Optional.ofNullable(annotationWrapper);
  }

  private class AnnotationWrapper {
    private final Annotation annotation;
    private final String databaseId;
    private final SqlCommandType sqlCommandType;

    AnnotationWrapper(Annotation annotation) {
      super();
      this.annotation = annotation;
      if (annotation instanceof Select) {
        databaseId = ((Select) annotation).databaseId();
        sqlCommandType = SqlCommandType.SELECT;
      } else if (annotation instanceof Update) {
        databaseId = ((Update) annotation).databaseId();
        sqlCommandType = SqlCommandType.UPDATE;
      } else if (annotation instanceof Insert) {
        databaseId = ((Insert) annotation).databaseId();
        sqlCommandType = SqlCommandType.INSERT;
      } else if (annotation instanceof Delete) {
        databaseId = ((Delete) annotation).databaseId();
        sqlCommandType = SqlCommandType.DELETE;
      } else if (annotation instanceof SelectProvider) {
        databaseId = ((SelectProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.SELECT;
      } else if (annotation instanceof UpdateProvider) {
        databaseId = ((UpdateProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.UPDATE;
      } else if (annotation instanceof InsertProvider) {
        databaseId = ((InsertProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.INSERT;
      } else if (annotation instanceof DeleteProvider) {
        databaseId = ((DeleteProvider) annotation).databaseId();
        sqlCommandType = SqlCommandType.DELETE;
      } else {
        sqlCommandType = SqlCommandType.UNKNOWN;
        if (annotation instanceof Options) {
          databaseId = ((Options) annotation).databaseId();
        } else if (annotation instanceof SelectKey) {
          databaseId = ((SelectKey) annotation).databaseId();
        } else {
          databaseId = "";
        }
      }
    }

    Annotation getAnnotation() {
      return annotation;
    }

    SqlCommandType getSqlCommandType() {
      return sqlCommandType;
    }

    String getDatabaseId() {
      return databaseId;
    }
  }
}
