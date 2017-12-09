package com.github.aucguy.optifinegradle.patcher;

import static com.github.aucguy.optifinegradle.OptifineConstants.*;
import static net.minecraftforge.gradle.patcher.PatcherConstants.TASK_PROJECT_GEN_PATCHES;

import java.io.File;

import org.gradle.api.tasks.bundling.Zip;

import com.github.aucguy.optifinegradle.ExtractRenames;
import com.github.aucguy.optifinegradle.OptifinePlugin;

import net.minecraftforge.gradle.patcher.PatcherPlugin;
import net.minecraftforge.gradle.patcher.PatcherProject;
import net.minecraftforge.gradle.patcher.TaskGenPatches;

public class OptifinePatcherPlugin extends PatcherPlugin
{
    OptifinePlugin delegate;
    
    public OptifinePatcherPlugin()
    {
        super();
        delegate = new OptifinePlugin(this);
        delegate.init();
    }
    
    @Override
    public void applyPlugin()
    {
        super.applyPlugin();
        delegate.applyPlugin();
        
        TaskGenPatches optifineGenPatches = makeTask(TASK_GEN_PATCHES, TaskGenPatches.class);
        {
            optifineGenPatches.setPatchDir(delayedFile(OPTIFINE_PATCH_DIR));
        }
        
        Zip zipPatches = makeTask(TASK_ZIP_PATCHES, Zip.class);
        {
            zipPatches.from(delayedFile(OPTIFINE_PATCH_DIR));
            zipPatches.setGroup(GROUP_OPTIFINE);
            zipPatches.setDescription("Create the optifine patch archive");
            zipPatches.dependsOn(optifineGenPatches);
        }
    }
    
    @Override
    public void afterEvaluate()
    {
        super.afterEvaluate();
        delegate.afterEvaluate();
        
        final PatcherProject projectMod = patchersList.get(patchersList.size() - 1);
        
        ExtractRenames extractRenames = (ExtractRenames) project.getTasks().getByName(TASK_EXTRACT_RENAMES);
        {
            extractRenames.inZip = delayedFile(PATCH_RENAMES);
        }
        
        TaskGenPatches modGenPatches = (TaskGenPatches) project.getTasks().getByName(projectString(TASK_PROJECT_GEN_PATCHES, projectMod));
        TaskGenPatches optifineGenPatches = (TaskGenPatches) project.getTasks().getByName(TASK_GEN_PATCHES);
        {
            for(File file : modGenPatches.getOriginalSource())
            {
                optifineGenPatches.addOriginalSource(file);
            }
            for(File file : modGenPatches.getChangedSource())
            {
                optifineGenPatches.addChangedSource(file);
            }
            for(Object dependency : modGenPatches.getDependsOn())
            {
                optifineGenPatches.dependsOn(dependency);
            }
        }
        
        Zip zipPatches = (Zip) project.getTasks().getByName(TASK_ZIP_PATCHES);
        {
            File out = delayedFile(OPTIFINE_PATCH_ZIP).call();
            zipPatches.setDestinationDir(out.getParentFile());
            zipPatches.setArchiveName(out.getName());
            zipPatches.from(delayedFile(PATCH_RENAMES));
        }
    }
}
