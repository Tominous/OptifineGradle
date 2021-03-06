package com.github.aucguy.optifinegradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import net.md_5.specialsource.JarMapping;
import net.minecraftforge.gradle.util.caching.Cached;
import net.minecraftforge.gradle.util.delayed.DelayedString;

public class JoinJars extends AsmProcessingTask
{
    @InputFile
    public Object       client;

    @InputFile
    public Object       optifine;

    @InputFile
    public Object       srg;
    
    @InputFile
    public Object		renames;
    
    @OutputFile
    @Cached
    public Object       classList;
    
    @Input
    private Set<String> exclusions = new HashSet<String>();
    
    protected Remapper mapping;
    protected Map<String, String> srgMapping;
    protected Set<String> optifineClasses = new HashSet<String>();

    @Override
    public void middle() throws IOException
    {
        Properties properties = new Properties();
        try(InputStream stream = IOManager.openFileForReading(this, renames))
        {
    	    properties.load(stream);
        }
    	mapping = new SimpleRemapper((Map) properties);
    	JarMapping jarMapping = new JarMapping();
    	jarMapping.loadMappings(getProject().file(srg));
    	srgMapping = jarMapping.classes;
    	copyJars(optifine, client);
    	
    	try(OutputStream classListOutput = IOManager.openFileForWriting(this, classList))
    	{
        	for(String name : optifineClasses)
        	{
        	    classListOutput.write(name.getBytes("UTF-8"));
        	    classListOutput.write('\n');
        	}
    	}
    }

	@Override
	protected byte[] asRead(Object inJar, final String name, byte[] data)
	{
        if (inJar == optifine && name.endsWith(".class"))
        {
            addObfClass(name);
            data = processAsm(data, new Function<ClassVisitor, ClassVisitor>()
            {
				@Override
				public ClassVisitor apply(ClassVisitor visitor)
				{
					ClassVisitor transformer = new ClassVisitor(Opcodes.ASM5, visitor)
			        {
				        @Override
				        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value)
				        {
				            if (name.equals("__OBFID"))
				            {
				                return null;
				            }
				            return super.visitField(access, name, desc, signature, value);
				        }
			        };
					transformer = new ClassRemapper(transformer, mapping);
			        return transformer;
				}
            });
        }
        return data;
	}

    protected boolean acceptsFile(String file)
    {
        for (String i : exclusions)
        {
            if (file.startsWith(i))
                return false;
        }
        return true;
    }

    public void exclude(String excl)
    {
        exclusions.add(excl);
    }
    
    public static String resolveString(Object obj)
    {
    	if(obj instanceof DelayedString)
    	{
    		return ((DelayedString) obj).call();
    	}
    	return (String) obj;
    }
    
    public void addObfClass(String name)
    {
        if (name.endsWith(".class"))
        {
            name = name.substring(0, name.length() - 6);
        }
        if (srgMapping.containsKey(name))
        {
            name = srgMapping.get(name);
            if (name.contains("$"))
            {
                name = name.split("\\$")[0];
            }
        }
        name = name.replace('/', '.');
        optifineClasses.add(name);
    }
}
