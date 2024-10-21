package io.metersphere.api.controller;

import io.metersphere.api.dto.definition.ApiDefinitionBatchExportRequest;
import io.metersphere.api.dto.export.MetersphereApiScenarioExportResponse;
import io.metersphere.api.dto.scenario.ApiScenarioImportRequest;
import io.metersphere.api.utils.ApiDataUtils;
import io.metersphere.functional.domain.ExportTask;
import io.metersphere.project.domain.Project;
import io.metersphere.sdk.constants.SessionConstants;
import io.metersphere.sdk.util.JSON;
import io.metersphere.sdk.util.MsFileUtils;
import io.metersphere.system.base.BaseTest;
import io.metersphere.system.constants.ExportConstants;
import io.metersphere.system.controller.handler.ResultHolder;
import io.metersphere.system.dto.AddProjectRequest;
import io.metersphere.system.log.constants.OperationLogModule;
import io.metersphere.system.manager.ExportTaskManager;
import io.metersphere.system.service.CommonProjectService;
import io.metersphere.system.uid.IDGenerator;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiScenarioControllerImportAndExportTests extends BaseTest {

    private static final String URL_POST_IMPORT = "/api/scenario/import";

    private static final String URL_POST_EXPORT = "/api/scenario/export/";

    private static Project project;

    @Resource
    private CommonProjectService commonProjectService;

    @BeforeEach
    public void initTestData() {
        //文件管理专用项目
        if (project == null) {
            AddProjectRequest initProject = new AddProjectRequest();
            initProject.setOrganizationId("100001");
            initProject.setName("场景导入专用");
            initProject.setDescription("场景导入专用项目");
            initProject.setEnable(true);
            initProject.setUserIds(List.of("admin"));
            project = commonProjectService.add(initProject, "admin", "/organization-project/add", OperationLogModule.SETTING_ORGANIZATION_PROJECT);
            //            ArrayList<String> moduleList = new ArrayList<>(List.of("workstation", "testPlan", "bugManagement", "caseManagement", "apiTest", "uiTest", "loadTest"));
            //            Project updateProject = new Project();
            //            updateProject.setId(importProject.getId());
            //            updateProject.setModuleSetting(JSON.toJSONString(moduleList));
            //            projectMapper.updateByPrimaryKeySelective(updateProject);
        }
    }

    @Test
    @Order(1)
    public void testImport() throws Exception {
        ApiScenarioImportRequest request = new ApiScenarioImportRequest();
        request.setProjectId(project.getId());
        request.setType("jmeter");
        String importType = "jmeter";
        String fileSuffix = "jmx";
        FileInputStream inputStream = new FileInputStream(new File(Objects.requireNonNull(this.getClass().getClassLoader().getResource("file/import-scenario/" + importType + "/simple." + fileSuffix)).getPath()));
        MockMultipartFile file = new MockMultipartFile("file", "simple." + fileSuffix, MediaType.APPLICATION_OCTET_STREAM_VALUE, inputStream);
        MultiValueMap<String, Object> paramMap = new LinkedMultiValueMap<>();
        paramMap.add("request", JSON.toJSONString(request));
        paramMap.add("file", file);
        this.requestMultipartWithOkAndReturn(URL_POST_IMPORT, paramMap);
    }

    @Resource
    private ExportTaskManager exportTaskManager;

    @Test
    @Order(2)
    public void testExport() throws Exception {
        MsFileUtils.deleteDir("/tmp/api-scenario-export/");

        ApiDefinitionBatchExportRequest exportRequest = new ApiDefinitionBatchExportRequest();
        String fileId = IDGenerator.nextStr();
        exportRequest.setProjectId(project.getId());
        exportRequest.setFileId(fileId);
        exportRequest.setSelectAll(true);
        exportRequest.setExportApiCase(true);
        exportRequest.setExportApiMock(true);
        MvcResult mvcResult = this.requestPostWithOkAndReturn(URL_POST_EXPORT + "metersphere", exportRequest);
        String returnData = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

        JSON.parseObject(returnData, ResultHolder.class).getData().toString();
        Assertions.assertTrue(StringUtils.isNotBlank(fileId));
        List<ExportTask> taskList = exportTaskManager.getExportTasks(exportRequest.getProjectId(), null, null, "admin", fileId);
        while (CollectionUtils.isEmpty(taskList)) {
            Thread.sleep(1000);
            taskList = exportTaskManager.getExportTasks(exportRequest.getProjectId(), null, null, "admin", fileId);
        }

        ExportTask task = taskList.getFirst();
        while (!StringUtils.equalsIgnoreCase(task.getState(), ExportConstants.ExportState.SUCCESS.name())) {
            Thread.sleep(1000);
            task = exportTaskManager.getExportTasks(exportRequest.getProjectId(), null, null, "admin", fileId).getFirst();
        }

        mvcResult = this.download(exportRequest.getProjectId(), fileId);

        byte[] fileBytes = mvcResult.getResponse().getContentAsByteArray();

        File zipFile = new File("/tmp/api-scenario-export/downloadFiles.zip");
        FileUtils.writeByteArrayToFile(zipFile, fileBytes);

        File[] files = MsFileUtils.unZipFile(zipFile, "/tmp/api-scenario-export/unzip/");
        assert files != null;
        Assertions.assertEquals(files.length, 1);
        String fileContent = FileUtils.readFileToString(files[0], StandardCharsets.UTF_8);

        MetersphereApiScenarioExportResponse exportResponse = ApiDataUtils.parseObject(fileContent, MetersphereApiScenarioExportResponse.class);

        Assertions.assertEquals(exportResponse.getExportScenarioList().size(), 3);

        MsFileUtils.deleteDir("/tmp/api-scenario-export/");
    }

    @Test
    @Order(3)
    public void testImportMs() throws Exception {
        ApiScenarioImportRequest request = new ApiScenarioImportRequest();
        request.setProjectId(project.getId());
        request.setType("metersphere");
        String importType = "metersphere";
        FileInputStream inputStream = new FileInputStream(new File(Objects.requireNonNull(this.getClass().getClassLoader().getResource("file/import-scenario/" + importType + "/all.ms")).getPath()));
        MockMultipartFile file = new MockMultipartFile("file", "all.ms", MediaType.APPLICATION_OCTET_STREAM_VALUE, inputStream);
        MultiValueMap<String, Object> paramMap = new LinkedMultiValueMap<>();
        paramMap.add("request", JSON.toJSONString(request));
        paramMap.add("file", file);
        this.requestMultipartWithOkAndReturn(URL_POST_IMPORT, paramMap);
    }

    private MvcResult download(String projectId, String fileId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/scenario/download/file/" + projectId + "/" + fileId)
                .header(SessionConstants.HEADER_TOKEN, sessionId)
                .header(SessionConstants.CSRF_TOKEN, csrfToken)).andReturn();
    }
}