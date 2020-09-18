/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
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
package org.jkiss.dbeaver.ui.navigator.actions;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.edit.DBEObjectMaker;
import org.jkiss.dbeaver.model.navigator.*;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithProgress;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.TasksJob;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.css.CSSUtils;
import org.jkiss.dbeaver.ui.css.DBStyles;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditor;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.internal.UINavigatorMessages;

import java.util.*;
import java.util.List;

public class NavigatorHandlerObjectDelete extends NavigatorHandlerObjectBase implements IElementUpdater {
    private static final Log log = Log.getLog(NavigatorHandlerObjectDelete.class);

    private IStructuredSelection structSelection;
    private Boolean deleteAll;
    private final Map<String, Object> deleteOptions = new HashMap<>();
    private final List<DBRRunnableWithProgress> tasksToExecute = new ArrayList<>();

    private final Collection<DBNDatabaseNode> nodesToDelete = new ArrayList<>(1);
    private final Collection<DBNResource> resourcesToDelete = new ArrayList<>(1);
    private final Collection<DBNLocalFolder> foldersToDelete = new ArrayList<>(1);
    private boolean showCascade = false;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        this.structSelection = null;
        this.deleteAll = null;
        this.deleteOptions.clear();
        this.tasksToExecute.clear();

        final IWorkbenchWindow activeWorkbenchWindow = HandlerUtil.getActiveWorkbenchWindow(event);
        final ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection) {
            structSelection = (IStructuredSelection)selection;
            if (structSelection.size() > 1) {
                prepareDeletionOfMultipleObjects(activeWorkbenchWindow);
            } else {
                for (Object element : structSelection) {
                    if (element instanceof DBNDatabaseNode) {
                        deleteObject(activeWorkbenchWindow, (DBNDatabaseNode) element);
                    } else if (element instanceof DBNResource) {
                        deleteResource(activeWorkbenchWindow, (DBNResource) element);
                    } else if (element instanceof DBNLocalFolder) {
                        deleteLocalFolder(activeWorkbenchWindow, (DBNLocalFolder) element);
                    } else {
                        log.warn("Don't know how to delete element '" + element + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (deleteAll != null && !deleteAll) {
                        break;
                    }
                }
            }
        }
        if (!tasksToExecute.isEmpty()) {
            TasksJob.runTasks(tasksToExecute.size() > 1 ? "Delete " + tasksToExecute.size() + " objects" : "Delete object", tasksToExecute);
            tasksToExecute.clear();
        }

        nodesToDelete.clear();
        resourcesToDelete.clear();
        foldersToDelete.clear();
        showCascade = false;

        return null;
    }

    private void deleteLocalFolder(IWorkbenchWindow workbenchWindow, DBNLocalFolder localFolder) {
        ConfirmResult confirmResult = confirmObjectDelete(workbenchWindow, localFolder, false, null, false);
        if (confirmResult == ConfirmResult.NO) {
            return;
        }
        localFolder.getDataSourceRegistry().removeFolder(localFolder.getFolder(), false);
        DBNModel.updateConfigAndRefreshDatabases(localFolder);
    }

    private void deleteResource(IWorkbenchWindow workbenchWindow, final DBNResource resourceNode) {
        ConfirmResult confirmResult = confirmObjectDelete(workbenchWindow, resourceNode, false, null, false);
        if (confirmResult == ConfirmResult.NO) {
            return;
        }
        final IResource resource = resourceNode.getResource();
        try {
            if (resource instanceof IFolder) {
                ((IFolder)resource).delete(true, true, new NullProgressMonitor());
            } else if (resource instanceof IProject) {
                // Delete project (with all contents)
                final boolean deleteContent = UIUtils.confirmAction(workbenchWindow.getShell(), "Delete project", "Delete project '" + resource.getName() + "' contents?");
                ((IProject) resource).delete(deleteContent, true, new NullProgressMonitor());
            } else if (resource != null) {
                resource.delete(IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            DBWorkbench.getPlatformUI().showError("Resource delete error", "Error deleting '" + resource.getFullPath().toString() + "'", e);
        }
    }

    private boolean deleteObject(IWorkbenchWindow workbenchWindow, DBNDatabaseNode node) {
        try {
            if (!(node.getParentNode() instanceof DBNContainer)) {
                throw new DBException("Node '" + node + "' doesn't have a container");
            }

            // Try to delete object using object manager
            DBSObject object = node.getObject();
            if (object == null) {
                throw new DBException("Can't delete node with null object");
            }
            DBEObjectMaker objectMaker = DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(object.getClass(), DBEObjectMaker.class);
            if (objectMaker == null) {
                throw new DBException("Object maker not found for type '" + object.getClass().getName() + "'"); //$NON-NLS-2$
            }
            boolean supportsCascade = (objectMaker.getMakerOptions(object.getDataSource()) & DBEObjectMaker.FEATURE_DELETE_CASCADE) != 0;

            CommandTarget commandTarget = getCommandTarget(
                workbenchWindow,
                node.getParentNode(),
                object.getClass(),
                false);

            if (deleteAll == null || !deleteAll) {
                this.deleteOptions.clear();
            }

            ConfirmResult confirmResult = ConfirmResult.YES;
            if (!object.isPersisted() || commandTarget.getEditor() != null) {
                // Not a real object delete because it's not persisted
                // There should be command context somewhere
                if (deleteNewObject(workbenchWindow, node)) {
                    return true;
                }
                // No direct editor host found for this object -
                // try to find corresponding command context
                // and execute command within it
            } else {
                // Persisted object - confirm delete
                // Show "View script" only if we are not in some editor (because it have its own "View script" button)
                confirmResult = confirmObjectDelete(workbenchWindow, node, supportsCascade, deleteOptions, commandTarget.getContext() != null && commandTarget.getEditor() == null );
                if (confirmResult == ConfirmResult.NO) {
                    return false;
                }
            }

            objectMaker.deleteObject(commandTarget.getContext(), node.getObject(), deleteOptions);
            if (confirmResult == ConfirmResult.DETAILS) {
                if (!showScript(workbenchWindow, commandTarget.getContext(), deleteOptions, UINavigatorMessages.actions_navigator_delete_script)) {
                    commandTarget.getContext().resetChanges(true);
                    // Show confirmation again
                    return deleteObject(workbenchWindow, node);
                }
            }

            if (commandTarget.getEditor() == null && commandTarget.getContext() != null) {
                // Persist object deletion - only if there is no host editor and we have a command context
                ObjectSaver deleter = new ObjectSaver(commandTarget.getContext(), deleteOptions);
//                DBeaverUI.runInProgressDialog(deleter);
                tasksToExecute.add(deleter);
            }
        } catch (Throwable e) {
            DBWorkbench.getPlatformUI().showError(UINavigatorMessages.actions_navigator_error_dialog_delete_object_title, "Can't delete object '" + node.getNodeName() + "'", e);
            return false;
        }
        return true;
    }

    private boolean deleteNewObject(IWorkbenchWindow workbenchWindow, DBNDatabaseNode node) {
        for (final IEditorReference editorRef : workbenchWindow.getActivePage().getEditorReferences()) {
            final IEditorPart editor = editorRef.getEditor(false);

            if (editor instanceof IDatabaseEditor) {
                final IEditorInput editorInput = editor.getEditorInput();
                if (editorInput instanceof IDatabaseEditorInput && ((IDatabaseEditorInput) editorInput).getDatabaseObject() == node.getObject()) {

                    ConfirmResult confirmResult = confirmObjectDelete(workbenchWindow, node, false, null, false);
                    if (confirmResult == ConfirmResult.NO) {
                        return true;
                    }
                    // Just close editor
                    // It should dismiss new object and remove navigator node
                    workbenchWindow.getActivePage().closeEditor(editor, false);
                    return true;
                }
            }
        }
        return false;
    }

    enum ConfirmResult {
        YES,
        NO,
        DETAILS,
    }

    private ConfirmResult confirmObjectDelete(final IWorkbenchWindow workbenchWindow, final DBNNode node, boolean supportsCascade, @Nullable Map<String, Object> deleteOptions, final boolean viewScript) {
        if (deleteAll != null) {
            return deleteAll ? ConfirmResult.YES : ConfirmResult.NO;
        }

        DeleteConfirmDialog dialog = new DeleteConfirmDialog(
            workbenchWindow,
            node,
            supportsCascade,
            viewScript);
        int result = dialog.open();

        if (deleteOptions != null && supportsCascade && dialog.cascadeCheck) {
            deleteOptions.put(DBEObjectMaker.OPTION_DELETE_CASCADE, Boolean.TRUE);
        }

        switch (result) {
            case IDialogConstants.YES_ID:
                return ConfirmResult.YES;
            case IDialogConstants.YES_TO_ALL_ID:
                deleteAll = true;
                return ConfirmResult.YES;
            case IDialogConstants.NO_ID:
                return ConfirmResult.NO;
            case IDialogConstants.CANCEL_ID:
            case -1:
                deleteAll = false;
                return ConfirmResult.NO;
            case IDialogConstants.DETAILS_ID:
                return ConfirmResult.DETAILS;
            default:
                log.warn("Unsupported confirmation dialog result: " + result); //$NON-NLS-1$
                return ConfirmResult.NO;
        }
    }

    @Override
    public void updateElement(final UIElement element, final Map parameters) {
//        if (!updateUI) {
//            return;
//        }
//        final ISelectionProvider selectionProvider = UIUtils.getSelectionProvider(element.getServiceLocator());
//        if (selectionProvider != null) {
//            ISelection selection = selectionProvider.getSelection();
//
//            if (selection instanceof IStructuredSelection && ((IStructuredSelection) selection).size() > 1) {
//                element.setText(UINavigatorMessages.actions_navigator_delete_objects);
//            } else {
//                DBNNode node = NavigatorUtils.getSelectedNode(selection);
//                if (node != null) {
//                    element.setText(UINavigatorMessages.actions_navigator_delete_ + " " + node.getNodeTypeLabel()  + " '" + node.getNodeName() + "'");
//                }
//            }
//        }
    }

    private class DeleteConfirmDialog extends MessageDialog {
        private final DBNNode node;
        private final boolean supportsCascade;
        private final boolean viewScript;
        private boolean cascadeCheck;

        DeleteConfirmDialog(IWorkbenchWindow workbenchWindow, DBNNode node, boolean supportsCascade, boolean viewScript) {
            super(
                workbenchWindow.getShell(),
                NLS.bind(node instanceof DBNLocalFolder ? UINavigatorMessages.confirm_local_folder_delete_title : UINavigatorMessages.confirm_entity_delete_title, node.getNodeType(), node.getNodeName()),
                DBeaverIcons.getImage(UIIcon.REJECT),
                NLS.bind(node instanceof DBNLocalFolder ? UINavigatorMessages.confirm_local_folder_delete_message : UINavigatorMessages.confirm_entity_delete_message, node.getNodeType(), node.getNodeName()),
                MessageDialog.CONFIRM, null, 0);
            this.node = node;
            this.supportsCascade = supportsCascade;
            this.viewScript = viewScript;
        }

        @Override
        protected Control createContents(Composite parent) {
            Control contents = super.createContents(parent);
            if (false && node instanceof DBNDatabaseNode) {
                CSSUtils.setCSSClass(contents, DBStyles.COLORED_BY_CONNECTION_TYPE);
                DBPDataSourceContainer ds = ((DBNDatabaseNode) node).getDataSourceContainer();
                Color connectionTypeColor = UIUtils.getConnectionTypeColor(ds.getConnectionConfiguration().getConnectionType());
                if (connectionTypeColor != null) {
                    UIUtils.setBackgroundForAll(getShell(), connectionTypeColor);
                }
            }
            return contents;
        }

        @Override
        protected Control createCustomArea(Composite parent) {
            if (supportsCascade) {
                Composite ph = UIUtils.createPlaceholder(parent, 1, 5);
                Button cascadeCheckButton = UIUtils.createCheckbox(ph, "Cascade delete", "Delete all dependent/child objects", false, 0);
                cascadeCheckButton.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        cascadeCheck = cascadeCheckButton.getSelection();
                    }
                });
            }
            return super.createCustomArea(parent);
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            createButton(parent, IDialogConstants.YES_ID, IDialogConstants.YES_LABEL, true);
            createButton(parent, IDialogConstants.NO_ID, IDialogConstants.NO_LABEL, false);
            if (structSelection.size() > 1) {
                createButton(parent, IDialogConstants.YES_TO_ALL_ID, IDialogConstants.YES_TO_ALL_LABEL, false);
                createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            }
            if (viewScript) {
                createButton(parent, IDialogConstants.DETAILS_ID, UINavigatorMessages.actions_navigator_view_script_button, false);
            }
        }
    }

    ///-------

//    private void exec() {
//        prepareObjects();
//        final DeleteMultipleObjectsConfirmDialog dialog = new DeleteMultipleObjectsConfirmDialog(); //fixme
//        final int result = dialog.open();
//        switch (result) {
//            case IDialogConstants.YES_ID:
//            case IDialogConstants.NO_ID:
//            case IDialogConstants.DETAILS_ID:
//            default: //fixme maybe there is more valid options
//        }
//
//    }

//    private void prepareObjects() {
//
//    }

    //not just database nodes, but _objects_ fixme: delete this comment
    private void prepareDeletionOfMultipleObjects(final IWorkbenchWindow window) {
        ConfirmResult confirmResult = confirmObjectsDeletion(window);
        switch (confirmResult) {
            case NO:
                return;
            case DETAILS:
                showScript();
            case YES:
               removeObjects(window);
        }
    }

    private ConfirmResult confirmObjectsDeletion(final IWorkbenchWindow window) {
        final DeleteMultipleObjectsConfirmDialog dialog = new DeleteMultipleObjectsConfirmDialog(window);
        final int result = dialog.open();
        if (dialog.cascadeCheck) { //todo verify correctness
            deleteOptions.put(DBEObjectMaker.OPTION_DELETE_CASCADE, Boolean.TRUE);
        }
        switch (result) {
            case IDialogConstants.YES_ID:
                deleteAll = true;
                return ConfirmResult.YES;
            case IDialogConstants.NO_ID:
                return ConfirmResult.NO;
            case IDialogConstants.CANCEL_ID:
            case -1:
                deleteAll = false;
                return ConfirmResult.NO;
            case IDialogConstants.DETAILS_ID:
                return ConfirmResult.DETAILS;
            default:
                log.warn("Unsupported confirmation dialog result: " + result); //$NON-NLS-1$
                return ConfirmResult.NO;
        }
    }

    private void removeObjects(final IWorkbenchWindow window) { //todo
        for (Object obj: structSelection) {

        }
    }

    private void showScript() { //todo

    }

    private void removeResource(final IWorkbenchWindow workbenchWindow, final DBNResource resourceNode) {
        final IResource resource = resourceNode.getResource();
        try {
            if (resource instanceof IFolder) {
                ((IFolder)resource).delete(true, true, new NullProgressMonitor());
            } else if (resource instanceof IProject) {
                // Delete project (with all contents)
                final boolean deleteContent = UIUtils.confirmAction(workbenchWindow.getShell(), "Delete project", "Delete project '" + resource.getName() + "' contents?");
                ((IProject) resource).delete(deleteContent, true, new NullProgressMonitor());
            } else if (resource != null) {
                resource.delete(IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            DBWorkbench.getPlatformUI().showError("Resource delete error", "Error deleting '" + resource.getFullPath().toString() + "'", e);
        }
    }

    private void removeLocalFolder(final DBNLocalFolder localFolder) {
        localFolder.getDataSourceRegistry().removeFolder(localFolder.getFolder(), false);
        DBNModel.updateConfigAndRefreshDatabases(localFolder);
    }

    private boolean removeDatabaseNode(final IWorkbenchWindow workbenchWindow, final DBNDatabaseNode node) {
        try {
            if (!(node.getParentNode() instanceof DBNContainer)) {
                throw new DBException("Node '" + node + "' doesn't have a container");
            }

            // Try to delete object using object manager
            DBSObject object = node.getObject();
            if (object == null) {
                throw new DBException("Can't delete node with null object");
            }
            DBEObjectMaker objectMaker = DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(object.getClass(), DBEObjectMaker.class);
            if (objectMaker == null) {
                throw new DBException("Object maker not found for type '" + object.getClass().getName() + "'"); //$NON-NLS-2$
            }
            boolean supportsCascade = (objectMaker.getMakerOptions(object.getDataSource()) & DBEObjectMaker.FEATURE_DELETE_CASCADE) != 0;

            CommandTarget commandTarget = getCommandTarget(
                    workbenchWindow,
                    node.getParentNode(),
                    object.getClass(),
                    false);

            if (deleteAll == null || !deleteAll) {
                this.deleteOptions.clear();
            }

            ConfirmResult confirmResult = ConfirmResult.YES;
            if (!object.isPersisted() || commandTarget.getEditor() != null) {
                // Not a real object delete because it's not persisted
                // There should be command context somewhere
                if (deleteNewObject(workbenchWindow, node)) {
                    return true;
                }
                // No direct editor host found for this object -
                // try to find corresponding command context
                // and execute command within it
            } else {
                // Persisted object - confirm delete
                // Show "View script" only if we are not in some editor (because it have its own "View script" button)
                confirmResult = confirmObjectDelete(workbenchWindow, node, supportsCascade, deleteOptions, commandTarget.getContext() != null && commandTarget.getEditor() == null );
                if (confirmResult == ConfirmResult.NO) {
                    return false;
                }
            }

            objectMaker.deleteObject(commandTarget.getContext(), node.getObject(), deleteOptions);
            if (confirmResult == ConfirmResult.DETAILS) {
                if (!showScript(workbenchWindow, commandTarget.getContext(), deleteOptions, UINavigatorMessages.actions_navigator_delete_script)) {
                    commandTarget.getContext().resetChanges(true);
                    // Show confirmation again
                    return deleteObject(workbenchWindow, node);
                }
            }

            if (commandTarget.getEditor() == null && commandTarget.getContext() != null) {
                // Persist object deletion - only if there is no host editor and we have a command context
                ObjectSaver deleter = new ObjectSaver(commandTarget.getContext(), deleteOptions);
//                DBeaverUI.runInProgressDialog(deleter);
                tasksToExecute.add(deleter);
            }
        } catch (Throwable e) {
            DBWorkbench.getPlatformUI().showError(UINavigatorMessages.actions_navigator_error_dialog_delete_object_title, "Can't delete object '" + node.getNodeName() + "'", e);
            return false;
        }
        return true;
    }

    private boolean removeNewDatabaseNode(final IWorkbenchWindow workbenchWindow, final DBNDatabaseNode node) {
        for (final IEditorReference editorRef : workbenchWindow.getActivePage().getEditorReferences()) {
            final IEditorPart editor = editorRef.getEditor(false);

            if (editor instanceof IDatabaseEditor) {
                final IEditorInput editorInput = editor.getEditorInput();
                if (editorInput instanceof IDatabaseEditorInput && ((IDatabaseEditorInput) editorInput).getDatabaseObject() == node.getObject()) {

                    ConfirmResult confirmResult = confirmObjectDelete(workbenchWindow, node, false, null, false);
                    if (confirmResult == ConfirmResult.NO) {
                        return true;
                    }
                    // Just close editor
                    // It should dismiss new object and remove navigator node
                    workbenchWindow.getActivePage().closeEditor(editor, false);
                    return true;
                }
            }
        }
        return false;
    }

    private class DeleteMultipleObjectsConfirmDialog extends MessageDialog {
        private boolean supportsCascade = true;

        private boolean viewScript = true;

        private boolean cascadeCheck = false;

        DeleteMultipleObjectsConfirmDialog(final IWorkbenchWindow window) {
            super(
                window.getShell(),
                "Title",
                DBeaverIcons.getImage(UIIcon.REJECT),
                "Message",
                MessageDialog.CONFIRM,
                null,
                0
            );
            //todo add viewScript
        }

        @Override
        protected Control createCustomArea(final Composite parent) {
            setUpObjectsTable(parent);
            setUpCascadeButton(parent);
            return super.createCustomArea(parent);
        }

        private void setUpObjectsTable(final Composite parent) {
            final Group objectsGroup = UIUtils.createControlGroup(parent, "Objects", 1, GridData.FILL_VERTICAL, 0); //fixme
            final Table objectsTable = new Table(objectsGroup, SWT.MULTI | SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.FILL); //fixme
//            objectsTable.setLinesVisible(true);
//            objectsTable.setLayoutData(new GridData()); //fixme
//            UIUtils.createTableColumn(objectsTable, SWT.LEFT, "Type"); //fixme
//            UIUtils.createTableColumn(objectsTable, SWT.LEFT, "Name"); //fixme
            final TableColumn typeColumn = new TableColumn(objectsTable, SWT.NONE); //fixme
            typeColumn.setText("Type");
            final TableColumn nameColumn = new TableColumn(objectsTable, SWT.NONE); //fixme
            nameColumn.setText("Type");
            for (Object obj : structSelection) {
                if (obj instanceof DBNDatabaseNode || obj instanceof DBNResource || obj instanceof DBNLocalFolder) {
                    final DBNNode node = (DBNNode) obj;
                    final TableItem tableItem = new TableItem(objectsTable, SWT.NONE);
                    tableItem.setText(new String[]{node.getNodeType(), node.getNodeName()});
                } else {
                    log.warn("Don't know how to delete element '" + obj + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            typeColumn.pack();
            nameColumn.pack();
        }

        private void setUpCascadeButton(final Composite parent) { //fixme refactor
            if (supportsCascade) {
                Composite ph = UIUtils.createPlaceholder(parent, 1, 5);
                Button cascadeCheckButton = UIUtils.createCheckbox(ph, "Cascade delete", "Delete all dependent/child objects", false, 0);
                cascadeCheckButton.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        cascadeCheck = cascadeCheckButton.getSelection();
                    }
                });
            }
        }

        @Override
        protected void createButtonsForButtonBar(final Composite parent) {
            createButton(parent, IDialogConstants.YES_ID, IDialogConstants.YES_LABEL, false);
            createButton(parent, IDialogConstants.NO_ID, IDialogConstants.NO_LABEL, true);
            if (viewScript) {
                createButton(parent, IDialogConstants.DETAILS_ID, UINavigatorMessages.actions_navigator_view_script_button, false);
            }
        }
    }
}
