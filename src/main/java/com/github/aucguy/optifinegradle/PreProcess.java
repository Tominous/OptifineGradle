package com.github.aucguy.optifinegradle;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.gradle.api.tasks.InputFile;

//TODO rename class/task
public class PreProcess extends AsmProcessingTask
{
    @InputFile
    public Object       inJar;

    @InputFile
    public Object       removedMethods;

    protected Map<String, String[]> removals;
    
    protected void middle() throws Throwable
    {
        Properties properties = new Properties();
        properties.load(manager.openFileSomewhereForReading(removedMethods));
        removals = new HashMap<String, String[]>();
        for(Object key : properties.keySet())
        {
            String[] value = properties.getProperty((String) key).split(",");
            if(value.length != 2)
            {
                throw(new RuntimeException("remove methods file invalid for key " + key));
            }
            for(int i=0; i<value.length; i++)
            {
                value[i] = value[i].trim();
            }
            removals.put(((String) key).trim(), value);
        }
        copyJars(inJar);
    }
    
    @Override
    protected byte[] asRead(Object inJar, String name, byte[] data)
    {
        final String className = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
        if(removals.containsKey(className))
        {
            return processAsm(data, visitor -> new MethodRemover(visitor, className, removals));
        }
        else
        {
            return data;
        }
    }
}
