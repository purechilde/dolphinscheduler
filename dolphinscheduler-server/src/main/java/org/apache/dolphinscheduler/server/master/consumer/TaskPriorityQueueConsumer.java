/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.master.consumer;

import com.alibaba.fastjson.JSONObject;
import org.apache.dolphinscheduler.common.enums.ExecutionStatus;
import org.apache.dolphinscheduler.common.enums.TaskType;
import org.apache.dolphinscheduler.common.enums.UdfType;
import org.apache.dolphinscheduler.common.model.TaskNode;
import org.apache.dolphinscheduler.common.process.ResourceInfo;
import org.apache.dolphinscheduler.common.task.AbstractParameters;
import org.apache.dolphinscheduler.common.task.datax.DataxParameters;
import org.apache.dolphinscheduler.common.task.procedure.ProcedureParameters;
import org.apache.dolphinscheduler.common.task.sql.SqlParameters;
import org.apache.dolphinscheduler.common.thread.Stopper;
import org.apache.dolphinscheduler.common.utils.*;
import org.apache.dolphinscheduler.dao.entity.*;
import org.apache.dolphinscheduler.server.builder.TaskExecutionContextBuilder;
import org.apache.dolphinscheduler.server.entity.*;
import org.apache.dolphinscheduler.server.master.dispatch.ExecutorDispatcher;
import org.apache.dolphinscheduler.server.master.dispatch.context.ExecutionContext;
import org.apache.dolphinscheduler.server.master.dispatch.enums.ExecutorType;
import org.apache.dolphinscheduler.server.master.dispatch.exceptions.ExecuteException;
import org.apache.dolphinscheduler.service.process.ProcessService;
import org.apache.dolphinscheduler.service.queue.TaskPriorityQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TaskUpdateQueue consumer
 */
@Component
public class TaskPriorityQueueConsumer extends Thread{

    /**
     * logger of TaskUpdateQueueConsumer
     */
    private static final Logger logger = LoggerFactory.getLogger(TaskPriorityQueueConsumer.class);

    /**
     * taskUpdateQueue
     */
    @Autowired
    private TaskPriorityQueue taskUpdateQueue;

    /**
     * processService
     */
    @Autowired
    private ProcessService processService;

    /**
     * executor dispatcher
     */
    @Autowired
    private ExecutorDispatcher dispatcher;

    @PostConstruct
    public void init(){
        super.setName("TaskUpdateQueueConsumerThread");
        super.start();
    }

    @Override
    public void run() {
        while (Stopper.isRunning()){
            try {
                // if not task , blocking here
                String taskPriorityInfo = taskUpdateQueue.take();

                TaskPriority taskPriority = TaskPriority.of(taskPriorityInfo);

                dispatch(taskPriority.getTaskId());
            }catch (Exception e){
                logger.error("dispatcher task error",e);
            }
        }
    }


    /**
     * dispatch task
     *
     * @param taskInstanceId taskInstanceId
     * @return result
     */
    private Boolean dispatch(int taskInstanceId){
        TaskExecutionContext context = getTaskExecutionContext(taskInstanceId);
        ExecutionContext executionContext = new ExecutionContext(context.toCommand(), ExecutorType.WORKER, context.getWorkerGroup());
        try {
            return dispatcher.dispatch(executionContext);
        } catch (ExecuteException e) {
            logger.error("execute exception", e);
            return false;
        }

    }

    /**
     * get TaskExecutionContext
     * @param taskInstanceId taskInstanceId
     * @return TaskExecutionContext
     */
    protected TaskExecutionContext getTaskExecutionContext(int taskInstanceId){
        TaskInstance taskInstance = processService.getTaskInstanceDetailByTaskId(taskInstanceId);

        // task type
        TaskType taskType = TaskType.valueOf(taskInstance.getTaskType());

        // task node
        TaskNode taskNode = JSONObject.parseObject(taskInstance.getTaskJson(), TaskNode.class);

        Integer userId = taskInstance.getProcessDefine() == null ? 0 : taskInstance.getProcessDefine().getUserId();
        Tenant tenant = processService.getTenantForProcess(taskInstance.getProcessInstance().getTenantId(), userId);

        // verify tenant is null
        if (verifyTenantIsNull(tenant, taskInstance)) {
            processService.changeTaskState(ExecutionStatus.FAILURE,
                    taskInstance.getStartTime(),
                    taskInstance.getHost(),
                    null,
                    null,
                    taskInstance.getId());
            return null;
        }
        // set queue for process instance, user-specified queue takes precedence over tenant queue
        String userQueue = processService.queryUserQueueByProcessInstanceId(taskInstance.getProcessInstanceId());
        taskInstance.getProcessInstance().setQueue(StringUtils.isEmpty(userQueue) ? tenant.getQueue() : userQueue);
        taskInstance.getProcessInstance().setTenantCode(tenant.getTenantCode());
        taskInstance.setExecutePath(getExecLocalPath(taskInstance));
        taskInstance.setResources(getResourceFullNames(taskNode));


        SQLTaskExecutionContext sqlTaskExecutionContext = new SQLTaskExecutionContext();
        DataxTaskExecutionContext dataxTaskExecutionContext = new DataxTaskExecutionContext();
        ProcedureTaskExecutionContext procedureTaskExecutionContext = new ProcedureTaskExecutionContext();


        // SQL task
        if (taskType == TaskType.SQL){
            setSQLTaskRelation(sqlTaskExecutionContext, taskNode);

        }

        // DATAX task
        if (taskType == TaskType.DATAX){
            setDataxTaskRelation(dataxTaskExecutionContext, taskNode);
        }


        // procedure task
        if (taskType == TaskType.PROCEDURE){
            setProcedureTaskRelation(procedureTaskExecutionContext, taskNode);
        }


        return TaskExecutionContextBuilder.get()
                .buildTaskInstanceRelatedInfo(taskInstance)
                .buildProcessInstanceRelatedInfo(taskInstance.getProcessInstance())
                .buildProcessDefinitionRelatedInfo(taskInstance.getProcessDefine())
                .buildSQLTaskRelatedInfo(sqlTaskExecutionContext)
                .buildDataxTaskRelatedInfo(dataxTaskExecutionContext)
                .buildProcedureTaskRelatedInfo(procedureTaskExecutionContext)
                .create();
    }

    /**
     * set procedure task relation
     * @param procedureTaskExecutionContext procedureTaskExecutionContext
     * @param taskNode taskNode
     */
    private void setProcedureTaskRelation(ProcedureTaskExecutionContext procedureTaskExecutionContext, TaskNode taskNode) {
        ProcedureParameters procedureParameters = JSONObject.parseObject(taskNode.getParams(), ProcedureParameters.class);
        int datasourceId = procedureParameters.getDatasource();
        DataSource datasource = processService.findDataSourceById(datasourceId);
        procedureTaskExecutionContext.setConnectionParams(datasource.getConnectionParams());
    }

    /**
     * set datax task relation
     * @param dataxTaskExecutionContext dataxTaskExecutionContext
     * @param taskNode taskNode
     */
    private void setDataxTaskRelation(DataxTaskExecutionContext dataxTaskExecutionContext, TaskNode taskNode) {
        DataxParameters dataxParameters = JSONObject.parseObject(taskNode.getParams(), DataxParameters.class);

        DataSource dataSource = processService.findDataSourceById(dataxParameters.getDataSource());
        DataSource dataTarget = processService.findDataSourceById(dataxParameters.getDataTarget());


        dataxTaskExecutionContext.setDataSourceId(dataxParameters.getDataSource());
        dataxTaskExecutionContext.setSourcetype(dataSource.getType().getCode());
        dataxTaskExecutionContext.setSourceConnectionParams(dataSource.getConnectionParams());

        dataxTaskExecutionContext.setDataTargetId(dataxParameters.getDataTarget());
        dataxTaskExecutionContext.setTargetType(dataTarget.getType().getCode());
        dataxTaskExecutionContext.setTargetConnectionParams(dataTarget.getConnectionParams());
    }

    /**
     * set SQL task relation
     * @param sqlTaskExecutionContext sqlTaskExecutionContext
     * @param taskNode taskNode
     */
    private void setSQLTaskRelation(SQLTaskExecutionContext sqlTaskExecutionContext, TaskNode taskNode) {
        SqlParameters sqlParameters = JSONObject.parseObject(taskNode.getParams(), SqlParameters.class);
        int datasourceId = sqlParameters.getDatasource();
        DataSource datasource = processService.findDataSourceById(datasourceId);
        sqlTaskExecutionContext.setConnectionParams(datasource.getConnectionParams());

        // whether udf type
        boolean udfTypeFlag = EnumUtils.isValidEnum(UdfType.class, sqlParameters.getType())
                && StringUtils.isNotEmpty(sqlParameters.getUdfs());

        if (udfTypeFlag){
            String[] udfFunIds = sqlParameters.getUdfs().split(",");
            int[] udfFunIdsArray = new int[udfFunIds.length];
            for(int i = 0 ; i < udfFunIds.length;i++){
                udfFunIdsArray[i]=Integer.parseInt(udfFunIds[i]);
            }

            List<UdfFunc> udfFuncList = processService.queryUdfFunListByids(udfFunIdsArray);
            sqlTaskExecutionContext.setUdfFuncList(udfFuncList);
        }
    }

    /**
     * get execute local path
     *
     * @return execute local path
     */
    private String getExecLocalPath(TaskInstance taskInstance){
        return FileUtils.getProcessExecDir(taskInstance.getProcessDefine().getProjectId(),
                taskInstance.getProcessDefine().getId(),
                taskInstance.getProcessInstance().getId(),
                taskInstance.getId());
    }


    /**
     *  whehter tenant is null
     * @param tenant tenant
     * @param taskInstance taskInstance
     * @return result
     */
    private boolean verifyTenantIsNull(Tenant tenant, TaskInstance taskInstance) {
        if(tenant == null){
            logger.error("tenant not exists,process instance id : {},task instance id : {}",
                    taskInstance.getProcessInstance().getId(),
                    taskInstance.getId());
            return true;
        }
        return false;
    }


    /**
     *  create project resource files
     */
    private List<String> getResourceFullNames(TaskNode taskNode){

        Set<Integer> resourceIdsSet = new HashSet<>();
        AbstractParameters baseParam = TaskParametersUtils.getParameters(taskNode.getType(), taskNode.getParams());

        if (baseParam != null) {
            List<ResourceInfo> projectResourceFiles = baseParam.getResourceFilesList();
            if (projectResourceFiles != null) {
                Stream<Integer> resourceInfotream = projectResourceFiles.stream().map(resourceInfo -> resourceInfo.getId());
                resourceIdsSet.addAll(resourceInfotream.collect(Collectors.toSet()));

            }
        }

        if (CollectionUtils.isEmpty(resourceIdsSet)){
            return null;
        }

        Integer[] resourceIds = resourceIdsSet.toArray(new Integer[resourceIdsSet.size()]);

        List<Resource> resources = processService.listResourceByIds(resourceIds);

        List<String> resourceFullNames = resources.stream()
                .map(resourceInfo -> resourceInfo.getFullName())
                .collect(Collectors.toList());

        return resourceFullNames;
    }
}
