/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.production.forms;

import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.enterprise.context.SessionScoped;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.config.ConfigCore;
import org.kitodo.config.enums.ParameterCore;
import org.kitodo.data.database.beans.Batch;
import org.kitodo.data.database.beans.Folder;
import org.kitodo.data.database.beans.Process;
import org.kitodo.data.database.beans.Property;
import org.kitodo.data.database.beans.Task;
import org.kitodo.data.database.beans.User;
import org.kitodo.data.database.enums.TaskEditType;
import org.kitodo.data.database.enums.TaskStatus;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.data.exceptions.DataException;
import org.kitodo.export.ExportDms;
import org.kitodo.export.TiffHeader;
import org.kitodo.production.dto.TaskDTO;
import org.kitodo.production.enums.GenerationMode;
import org.kitodo.production.enums.ObjectType;
import org.kitodo.production.helper.CustomListColumnInitializer;
import org.kitodo.production.helper.Helper;
import org.kitodo.production.helper.WebDav;
import org.kitodo.production.helper.batch.BatchTaskHelper;
import org.kitodo.production.helper.tasks.TaskManager;
import org.kitodo.production.metadata.MetadataLock;
import org.kitodo.production.model.LazyDTOModel;
import org.kitodo.production.model.Subfolder;
import org.kitodo.production.services.ServiceManager;
import org.kitodo.production.services.data.ProcessService;
import org.kitodo.production.services.data.TaskService;
import org.kitodo.production.services.file.SubfolderFactoryService;
import org.kitodo.production.services.image.ImageGenerator;
import org.kitodo.production.services.workflow.WorkflowControllerService;
import org.kitodo.production.thread.TaskImageGeneratorThread;

@Named("CurrentTaskForm")
@SessionScoped
public class CurrentTaskForm extends BaseForm {
    private static final Logger logger = LogManager.getLogger(CurrentTaskForm.class);
    private Process myProcess = new Process();
    private Task currentTask = new Task();
    private List<TaskDTO> selectedTasks = new ArrayList<>();
    private final WebDav myDav = new WebDav();
    private boolean onlyOpenTasks = false;
    private boolean onlyOwnTasks = false;
    private TaskStatus taskStatusRestriction = null;
    private boolean showAutomaticTasks = false;
    private boolean hideCorrectionTasks = false;
    private String scriptPath;
    private final String doneDirectoryName;
    private transient BatchTaskHelper batchHelper;
    private final WorkflowControllerService workflowControllerService = new WorkflowControllerService();
    private List<Property> properties;
    private Property property;
    private final String taskEditPath = MessageFormat.format(REDIRECT_PATH, "currentTasksEdit");
    private final String taskBatchEditPath = MessageFormat.format(REDIRECT_PATH, "taskBatchEdit");

    @Inject
    private CustomListColumnInitializer initializer;

    /**
     * Constructor.
     */
    public CurrentTaskForm() {
        super();
        super.setLazyDTOModel(new LazyDTOModel(ServiceManager.getTaskService()));
        doneDirectoryName = ConfigCore.getParameterOrDefaultValue(ParameterCore.DONE_DIRECTORY_NAME);
    }

    /**
     * Initialize the list of displayed list columns.
     */
    @PostConstruct
    public void init() {
        columns = new ArrayList<>();
        try {
            columns.add(ServiceManager.getListColumnService().getListColumnsForListAsSelectItemGroup("task"));
        } catch (DAOException e) {
            Helper.setErrorMessage(e.getLocalizedMessage(), logger, e);
        }
        selectedColumns = ServiceManager.getListColumnService().getSelectedListColumnsForListAndClient("task");
    }

    /**
     * Take over task by user which calls this method.
     *
     * @return page
     */
    public String takeOverTask() {
        if (this.currentTask.getProcessingStatus() != TaskStatus.OPEN) {
            Helper.setErrorMessage("stepInWorkError");
            return this.stayOnCurrentPage;
        } else {
            this.workflowControllerService.assignTaskToUser(this.currentTask);
            try {
                ServiceManager.getTaskService().save(this.currentTask);
            } catch (DataException e) {
                Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.TASK.getTranslationSingular() }, logger,
                    e);
            }
        }
        return taskEditPath + "&id=" + getTaskIdForPath();
    }

    /**
     * Edit task.
     *
     * @return page
     */
    public String editTask() {
        ServiceManager.getTaskService().refresh(this.currentTask);
        return taskEditPath + "&id=" + getTaskIdForPath();
    }

    /**
     * Take over batch of tasks - all tasks assigned to the same batch with the same
     * title.
     *
     * @return page for edit one task, page for edit many or stay on the same page
     */
    public String takeOverBatchTasks() {
        String taskTitle = this.currentTask.getTitle();
        List<Batch> batches = this.currentTask.getProcess().getBatches();

        if (batches.isEmpty()) {
            return takeOverTask();
        } else if (batches.size() == 1) {
            Integer batchId = batches.get(0).getId();
            List<Task> currentTasksOfBatch = ServiceManager.getTaskService().getCurrentTasksOfBatch(taskTitle, batchId);
            if (currentTasksOfBatch.isEmpty()) {
                return this.stayOnCurrentPage;
            } else if (currentTasksOfBatch.size() == 1) {
                return takeOverTask();
            } else {
                for (Task task : currentTasksOfBatch) {
                    processTask(task);
                }

                this.setBatchHelper(new BatchTaskHelper(currentTasksOfBatch));
                return taskBatchEditPath;
            }
        } else {
            Helper.setErrorMessage("multipleBatchesAssigned");
            return this.stayOnCurrentPage;
        }
    }

    /**
     * Update task which are available to take.
     *
     * @param task
     *            which is part of the batch
     */
    private void processTask(Task task) {
        if (task.getProcessingStatus().equals(TaskStatus.OPEN)) {
            task.setProcessingStatus(TaskStatus.INWORK);
            task.setEditType(TaskEditType.MANUAL_MULTI);
            task.setProcessingTime(new Date());
            User user = getUser();
            ServiceManager.getTaskService().replaceProcessingUser(task, user);
            if (Objects.isNull(task.getProcessingBegin())) {
                task.setProcessingBegin(new Date());
            }

            if (task.isTypeImagesRead() || task.isTypeImagesWrite()) {
                try {
                    URI imagesOrigDirectory = ServiceManager.getProcessService().getImagesOriginDirectory(false,
                        task.getProcess());
                    if (!ServiceManager.getFileService().fileExist(imagesOrigDirectory)) {
                        Helper.setErrorMessage("errorDirectoryNotFound", new Object[] {imagesOrigDirectory });
                    }
                } catch (Exception e) {
                    Helper.setErrorMessage("errorDirectoryRetrieve", new Object[] {"image" }, logger, e);
                }
                task.setProcessingTime(new Date());
                this.myDav.downloadToHome(task.getProcess(), !task.isTypeImagesWrite());
            }
        }

        try {
            ServiceManager.getTaskService().save(task);
        } catch (DataException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.TASK.getTranslationSingular() }, logger, e);
        }
    }

    /**
     * Edit batch of tasks - all tasks assigned to the same batch with the same
     * title.
     *
     * @return page for edit one task, page for edit many or stay on the same page
     */
    public String editBatchTasks() {
        String taskTitle = this.currentTask.getTitle();
        List<Batch> batches = this.currentTask.getProcess().getBatches();

        if (batches.isEmpty()) {
            return taskEditPath + "&id=" + getTaskIdForPath();
        } else if (batches.size() == 1) {
            Integer batchId = batches.get(0).getId();
            List<Task> currentTasksOfBatch = ServiceManager.getTaskService().getCurrentTasksOfBatch(taskTitle, batchId);
            if (currentTasksOfBatch.isEmpty()) {
                return this.stayOnCurrentPage;
            } else if (currentTasksOfBatch.size() == 1) {
                return taskEditPath + "&id=" + getTaskIdForPath();
            } else {
                this.setBatchHelper(new BatchTaskHelper(currentTasksOfBatch));
                return taskBatchEditPath;
            }
        } else {
            Helper.setErrorMessage("multipleBatchesAssigned");
            return this.stayOnCurrentPage;
        }
    }

    /**
     * Release task - set up task status to open and make available for other users
     * to take over.
     *
     * @return page
     */
    public String releaseTask() {
        try {
            this.workflowControllerService.unassignTaskFromUser(this.currentTask);
        } catch (DataException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.TASK.getTranslationSingular() }, logger, e);
            return this.stayOnCurrentPage;
        }
        return tasksPage;
    }

    /**
     * Close method task called by user action.
     *
     * @return page
     */
    public String closeTaskByUser() {
        try {
            this.workflowControllerService.closeTaskByUser(this.currentTask);
        } catch (DataException | IOException | DAOException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.TASK.getTranslationSingular() }, logger, e);
            return this.stayOnCurrentPage;
        }
        return tasksPage;
    }

    /**
     * Unlock the current task's process.
     *
     * @return stay on the current page
     */
    public String releaseLock() {
        MetadataLock.setFree(this.currentTask.getProcess().getId());
        return this.stayOnCurrentPage;
    }

    /**
     * Upload from home.
     *
     * @return String
     */
    @SuppressWarnings("unchecked")
    public String uploadFromHomeAlle() {
        List<URI> readyList = this.myDav.uploadAllFromHome(doneDirectoryName);
        List<URI> checkedList = new ArrayList<>();

        // go through the uploaded process IDs and set to complete
        if (!readyList.isEmpty() && this.onlyOpenTasks) {
            this.onlyOpenTasks = false;
            return tasksPage;
        }
        for (URI element : readyList) {
            String id = element.toString()
                    .substring(element.toString().indexOf('[') + 1, element.toString().indexOf(']')).trim();

            for (Task task : (List<Task>) lazyDTOModel.getEntities()) {
                // only when the task is already in edit mode, complete it
                if (task.getProcess().getId() == Integer.parseInt(id)
                        && task.getProcessingStatus() == TaskStatus.INWORK) {
                    this.currentTask = task;
                    if (Objects.nonNull(closeTaskByUser())) {
                        checkedList.add(element);
                    }
                    this.currentTask.setEditType(TaskEditType.MANUAL_MULTI);
                }
            }
        }

        this.myDav.removeAllFromHome(checkedList, URI.create(doneDirectoryName));
        Helper.setMessage("removed " + checkedList.size() + " directories from user home:", doneDirectoryName);
        return this.stayOnCurrentPage;
    }

    /**
     * Download to home page.
     *
     * @return String
     */
    public String downloadToHomePage() {
        download();
        // calcHomeImages();
        Helper.setMessage("Created directories in user home");
        return this.stayOnCurrentPage;
    }

    /**
     * Download to home.
     *
     * @return String
     */
    public String downloadToHomeHits() {
        download();
        // calcHomeImages();
        Helper.setMessage("Created directories in user home");
        return this.stayOnCurrentPage;
    }

    @SuppressWarnings("unchecked")
    private void download() {
        for (TaskDTO taskDTO : (List<TaskDTO>) lazyDTOModel.getEntities()) {
            Task task = new Task();
            try {
                task = ServiceManager.getTaskService().getById(taskDTO.getId());
            } catch (DAOException e) {
                Helper.setErrorMessage(ERROR_LOADING_ONE,
                    new Object[] {ObjectType.TASK.getTranslationSingular(), taskDTO.getId() }, logger, e);
            }
            if (task.getProcessingStatus() == TaskStatus.OPEN) {
                task.setProcessingStatus(TaskStatus.INWORK);
                task.setEditType(TaskEditType.MANUAL_MULTI);
                task.setProcessingTime(new Date());
                User user = getUser();
                ServiceManager.getTaskService().replaceProcessingUser(task, user);
                task.setProcessingBegin(new Date());
                Process process = task.getProcess();
                try {
                    ServiceManager.getProcessService().save(process);
                } catch (DataException e) {
                    Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.PROCESS.getTranslationSingular() },
                        logger, e);
                }
                this.myDav.downloadToHome(process, false);
            }
        }
    }

    /**
     * Generate all images.
     */
    public void generateAllImages() {
        generateImages(GenerationMode.ALL, "regenerateAllImagesStarted");
    }

    /**
     * Generate missing and damaged images.
     */
    public void generateMissingAndDamagedImages() {
        generateImages(GenerationMode.MISSING_OR_DAMAGED, "regenerateMissingAndDamagedImagesStarted");
    }

    /**
     * Generate missing images.
     */
    public void generateMissingImages() {
        generateImages(GenerationMode.MISSING, "regenerateMissingImagesStarted");
    }

    /**
     * Action that creates images.
     *
     * @param mode
     *            which function should be executed
     * @param messageKey
     *            message displayed to the user (key for resourcebundle)
     */
    private void generateImages(GenerationMode mode, String messageKey) {
        Folder generatorSource = myProcess.getProject().getGeneratorSource();
        List<Folder> contentFolders = currentTask.getContentFolders();
        if (Objects.isNull(generatorSource)) {
            Helper.setErrorMessage("noSourceFolderConfiguredInProject");
            return;
        }
        if (Objects.isNull(contentFolders)) {
            Helper.setErrorMessage("noImageFolderConfiguredInProject");
            return;
        }
        Subfolder sourceFolder = new Subfolder(myProcess, generatorSource);
        if (sourceFolder.listContents().isEmpty()) {
            Helper.setErrorMessage("emptySourceFolder");
        } else {
            List<Subfolder> outputs = SubfolderFactoryService.createAll(myProcess, contentFolders);
            ImageGenerator imageGenerator = new ImageGenerator(sourceFolder, mode, outputs);
            TaskManager.addTask(new TaskImageGeneratorThread(myProcess.getTitle(), imageGenerator));
            Helper.setMessage(messageKey);
        }
    }

    public String getScriptPath() {
        return this.scriptPath;
    }

    public void setScriptPath(String scriptPath) {
        this.scriptPath = scriptPath;
    }

    /**
     * Execute script.
     */
    public void executeScript() throws DAOException, DataException {
        Task task = ServiceManager.getTaskService().getById(this.currentTask.getId());
        ServiceManager.getTaskService().executeScript(task, this.scriptPath, false);
    }

    /**
     * Get current task.
     *
     * @return task
     */
    public Task getCurrentTask() {
        return this.currentTask;
    }

    /**
     * Set current task with edit mode set to empty String.
     *
     * @param task
     *            Object
     */
    public void setCurrentTask(Task task) {
        this.currentTask = task;
        this.currentTask.setLocalizedTitle(ServiceManager.getTaskService().getLocalizedTitle(task.getTitle()));
        this.myProcess = this.currentTask.getProcess();
        loadProcessProperties();
    }

    /**
     * Set task for given id.
     *
     * @param id
     *            passed as int
     */
    public void setTaskById(int id) {
        loadTaskById(id);
    }

    /**
     * Get list of selected Tasks.
     *
     * @return List of selected Tasks
     */
    public List<TaskDTO> getSelectedTasks() {
        return this.selectedTasks;
    }

    /**
     * Set selected tasks: Set tasks in old list to false and set new list to true.
     *
     * @param selectedTasks
     *            provided by data table
     */
    public void setSelectedTasks(List<TaskDTO> selectedTasks) {
        this.selectedTasks = selectedTasks;
    }

    /**
     * Downloads.
     */
    public void downloadTiffHeader() throws IOException {
        TiffHeader tiff = new TiffHeader(this.currentTask.getProcess());
        tiff.exportStart();
    }

    /**
     * Export DMS.
     */
    public void exportDMS() {
        ExportDms export = new ExportDms();
        try {
            export.startExport(this.currentTask.getProcess());
        } catch (DataException e) {
            Helper.setErrorMessage("errorExport", new Object[] {this.currentTask.getProcess().getTitle() }, logger, e);
        }
    }

    public void setTaskStatusRestriction(TaskStatus taskStatus) {
        ServiceManager.getTaskService().setTaskStatusRestriction(taskStatus);
        this.taskStatusRestriction = taskStatus;
    }

    public TaskStatus getTaskStatusRestriction() {
        return this.taskStatusRestriction;
    }

    /**
     * Check if it should show only own tasks.
     *
     * @return boolean
     */
    public boolean isOnlyOwnTasks() {
        return this.onlyOwnTasks;
    }

    /**
     * Set shown only tasks owned by currently logged user.
     *
     * @param onlyOwnTasks
     *            as boolean
     */
    public void setOnlyOwnTasks(boolean onlyOwnTasks) {
        this.onlyOwnTasks = onlyOwnTasks;
        ServiceManager.getTaskService().setOnlyOwnTasks(this.onlyOwnTasks);
    }

    /**
     * Check if it should show also automatic tasks.
     *
     * @return boolean
     */
    public boolean isShowAutomaticTasks() {
        return this.showAutomaticTasks;
    }

    /**
     * Checks if the task type is "generateImages" and thus the generate images links are shown.
     *
     * @return whether action links should be displayed
     */
    public boolean isShowingGenerationActions() {
        return currentTask.isTypeGenerateImages();
    }

    /**
     * Checks if folders for generation are configured in the project.
     * @return whether the folders are configured.
     */
    public boolean isImageGenerationPossible() {
        return TaskService.generatableFoldersFromProjects(Stream.of(currentTask.getProcess().getProject()))
                .findAny().isPresent();
    }

    /**
     * Set show automatic tasks.
     *
     * @param showAutomaticTasks
     *            as boolean
     */
    public void setShowAutomaticTasks(boolean showAutomaticTasks) {
        this.showAutomaticTasks = showAutomaticTasks;
        ServiceManager.getTaskService().setShowAutomaticTasks(showAutomaticTasks);
    }

    /**
     * Check if it should hide correction tasks.
     *
     * @return boolean
     */
    public boolean isHideCorrectionTasks() {
        return hideCorrectionTasks;
    }

    /**
     * Set hide correction tasks.
     *
     * @param hideCorrectionTasks
     *            as boolean
     */
    public void setHideCorrectionTasks(boolean hideCorrectionTasks) {
        this.hideCorrectionTasks = hideCorrectionTasks;
        ServiceManager.getTaskService().setHideCorrectionTasks(this.hideCorrectionTasks);
    }

    /**
     * Get property for process.
     *
     * @return property for process
     */
    public Property getProperty() {
        return this.property;
    }

    /**
     * Set property for process.
     *
     * @param property
     *            for process as Property object
     */
    public void setProperty(Property property) {
        this.property = property;
    }

    /**
     * Get list of process properties.
     *
     * @return list of process properties
     */
    public List<Property> getProperties() {
        return this.properties;
    }

    /**
     * Set list of process properties.
     *
     * @param properties
     *            for process as Property objects
     */
    public void setProperties(List<Property> properties) {
        this.properties = properties;
    }

    private void loadProcessProperties() {
        ServiceManager.getProcessService().refresh(this.myProcess);
        setProperties(this.myProcess.getProperties());
    }

    /**
     * Save current property.
     */
    public void saveCurrentProperty() {
        try {
            ServiceManager.getPropertyService().saveToDatabase(this.property);
            if (!this.myProcess.getProperties().contains(this.property)) {
                this.myProcess.getProperties().add(this.property);
            }
            ServiceManager.getProcessService().save(this.myProcess);
            Helper.setMessage("propertiesSaved");
        } catch (DataException | DAOException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.PROPERTY.getTranslationPlural() }, logger, e);
        }
        loadProcessProperties();
    }

    /**
     * Duplicate property.
     */
    public void duplicateProperty() {
        Property newProperty = ServiceManager.getPropertyService().transfer(this.property);
        try {
            newProperty.getProcesses().add(this.myProcess);
            this.myProcess.getProperties().add(newProperty);
            ServiceManager.getPropertyService().saveToDatabase(newProperty);
            Helper.setMessage("propertySaved");
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_SAVING, new Object[] {ObjectType.PROPERTY.getTranslationPlural() }, logger, e);
        }
        loadProcessProperties();
    }

    /**
     * Get batch helper.
     *
     * @return batch helper as BatchHelper object
     */
    public BatchTaskHelper getBatchHelper() {
        return this.batchHelper;
    }

    /**
     * Set batch helper.
     *
     * @param batchHelper
     *            as BatchHelper object
     */
    public void setBatchHelper(BatchTaskHelper batchHelper) {
        this.batchHelper = batchHelper;
    }

    /**
     * Method being used as viewAction for CurrentTaskForm.
     *
     * @param id
     *            ID of the task to load
     */
    public void loadTaskById(int id) {
        try {
            setCurrentTask(ServiceManager.getTaskService().getById(id));
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_LOADING_ONE, new Object[] {ObjectType.TASK.getTranslationSingular(), id },
                logger, e);
        }
    }

    /**
     * Retrieve and return the list of tasks that are assigned to the user that are
     * currently in progress.
     *
     * @return list of tasks that are currently assigned to the user that are
     *         currently in progress.
     */
    public List<Task> getTasksInProgress() {
        return ServiceManager.getUserService().getTasksInProgress(this.user);
    }

    /**
     * Get taskListPath.
     *
     * @return value of taskListPath
     */
    public String getTaskListPath() {
        return tasksPage;
    }

    private int getTaskIdForPath() {
        return Objects.isNull(this.currentTask.getId()) ? 0 : this.currentTask.getId();
    }

    /**
     * Return array of task custom column names.
     * @return array of task custom column names
     */
    public String[] getTaskCustomColumnNames() {
        return initializer.getTaskProcessProperties();
    }

    /**
     * Retrieve and return process property value of property with given name 'propertyName' from process of given
     * TaskDTO 'task'.
     * @param task the TaskDTO object from which the property value is retrieved
     * @param propertyName name of the property for the property value is retrieved
     * @return property value if process has property with name 'propertyName', empty String otherwise
     */
    public static String getTaskProcessPropertyValue(TaskDTO task, String propertyName) {
        return ProcessService.getPropertyValue(task.getProcess(), propertyName);
    }

    /**
     * Calculate and return age of given tasks process as a String.
     *
     * @param task TaskDTO object whose process is used
     * @return process age of given tasks process
     */
    public String getProcessDuration(TaskDTO task) {
        return ProcessService.getProcessDuration(task.getProcess());
    }

    /**
     * Changes the filter of the CurrentTaskForm and reloads it.
     *
     * @param filter
     *            the filter to apply
     * @return path of the page
     */
    public String changeFilter(String filter) {
        setFilter(filter);
        return filterList();
    }

    private String filterList() {
        this.selectedTasks.clear();
        return tasksPage;
    }

    @Override
    public void setFilter(String filter) {
        super.filter = filter;
    }

    /**
     * Check and return whether the process of the given task has any correction comments or not.
     *
     * @param task
     *          TaskDTO to check
     * @return 0, if process of given task has no correction comment
     *         1, if process of given task has correction comments that are all corrected
     *         2, if process of given task has at least one open correction comment
     */
    public int hasCorrectionComment(TaskDTO task) {
        try {
            return ProcessService.hasCorrectionComment(task.getProcess().getId()).getValue();
        } catch (DAOException e) {
            Helper.setErrorMessage(ERROR_LOADING_ONE, new Object[] {ObjectType.PROCESS.getTranslationSingular(),
                    task.getProcess().getId() }, logger, e);
            return 0;
        }
    }

    /**
     * Retrieve correction comments of process of given task and return them as a tooltip String.
     *
     * @param taskDTO
     *          task for which comment tooltip is created and returned
     * @return String containing correction comment messages for process of given task
     */
    public String getCorrectionMessages(TaskDTO taskDTO) {
        try {
            return ServiceManager.getProcessService().createCorrectionMessagesTooltip(taskDTO.getProcess());
        } catch (DAOException e) {
            Helper.setErrorMessage(e);
            return "";
        }
    }
}
