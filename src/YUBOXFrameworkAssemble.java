package org.yubox.arduinoplugin;

import java.util.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

import java.io.OutputStreamWriter;
import java.io.FileOutputStream;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import processing.app.PreferencesData;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.tools.Tool;
import processing.app.packages.UserLibraryFolder;

import org.apache.commons.io.FileUtils;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.CompressorOutputStream;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

public class YUBOXFrameworkAssemble
implements Tool
{
    Editor editor;

    public void init(Editor editor) { this.editor = editor; }
    public String getMenuTitle() { return "YUBOX - Assemble HTML Interface"; }
    public void run()
    {
        _assembleYuboxInterface();
    }

    private void _assembleYuboxInterface()
    {
        // Verificar que realmente estoy en una plataforma ESP32
        if (!PreferencesData.get("target_platform").contentEquals("esp32")) {
            System.err.println();
            editor.statusError("SPIFFS Not Supported on "+PreferencesData.get("target_platform"));
            return;
        }

        // Construir lista de directorios a usar para HTML
        List<File> template_dirs = _buildDataTemplateDirList();
        if (template_dirs == null) return;
        System.out.println("Searching content to assemble in the following directories:");
        for (File d : template_dirs) {
            System.out.println("\t" + d.getAbsolutePath());
        }

        // Recolectar contenido y módulos disponibles de la lista de directorios
        HashMap<String, HashMap<String, Object>> content = new HashMap<String, HashMap<String, Object>>();
        HashMap<String, HashMap<String, Object>> modules = new HashMap<String, HashMap<String, Object>>();
        try {
            _loadAvailableModules(template_dirs, content, modules);
        } catch (IOException e) {
            // Uno o más archivos no se pudieron leer
            System.err.println();
            editor.statusError(e);
            return;
        }

        // Reporte antes de generación
        System.out.println();
        System.out.println("INFO: Modules available and enabled in project:");
        for (String m : modules.keySet()) {
            System.out.println();
            System.out.println("\tModule: "+(String)modules.get(m).get("module_name"));
            System.out.println("\tProvides content for following templates:");
            HashMap<String, HashMap<String, Object>> templates =
                (HashMap<String, HashMap<String, Object>>)(modules.get(m).get("templates"));
            for (String tpl : templates.keySet()) {
                System.out.println(String.format("\t\t%1$s : %2$s", tpl, ((File)(templates.get(tpl).get("module_content_path"))).getAbsolutePath()));
            }
        }
        System.out.println();
        System.out.println("INFO: Content to copy and parse into project:");
        for (String t : content.keySet()) {
            System.out.println();
            System.out.println("\tFile: "+((String)(content.get(t).get("target"))));
            if (content.get(t).containsKey("template_path")) {
                System.out.println("\tType: template");
                System.out.println("\tPath: "+ ((File)(content.get(t).get("template_path"))).getAbsolutePath());
            }
            if (content.get(t).containsKey("source_path")) {
                System.out.println("\tType: copied file");
                System.out.println("\tPath: "+ ((File)(content.get(t).get("source_path"))).getAbsolutePath());
            }
        }

        // Leer lista del resto de parámetros para habilitar módulos
        ArrayList<String> modules_enabled = new ArrayList<>();
        String module_active;
        try {
            module_active = _loadEnabledModuleList(modules, modules_enabled);
            if (module_active == null) return;
        } catch (IOException e) {
            // Uno o más archivos no se pudieron leer
            System.err.println();
            editor.statusError(e);
            return;
        }
        // El código javascript busca la clase set-active para activar esta pestaña inicialmente
        System.out.println();
        System.out.println(String.format("INFO: marking module %1$s as initial active module.", module_active));
        modules.get(module_active).put("module_active", "set-active");

        // Para cada módulo se carga el archivo en la variable module_content
        try {
            for (String m : modules.keySet()) {
                HashMap<String, HashMap<String, Object>> templates =
                    (HashMap<String, HashMap<String, Object>>)(modules.get(m).get("templates"));
                for (String tpl : templates.keySet()) {
                    File f = (File)(templates.get(tpl).get("module_content_path"));
                    System.out.println(String.format("INFO: loading %1$s:%2$s from %3$s ...",
                        m, tpl, f.getAbsolutePath()));
                    templates.get(tpl).put("module_content", FileUtils.readFileToString(f, "UTF-8"));
                }
            }
        } catch (IOException e) {
            // Uno o más archivos no se pudieron leer
            System.err.println();
            editor.statusError(e);
            return;
        }

        // Finalmente se genera el contenido
        ArrayList<String> manifest = new ArrayList<>();
        File dataDir = new File(editor.getSketch().getFolder(), "data");
        if (!dataDir.exists()) {
            System.out.println("INFO: creando directorio data ...");
            if (!dataDir.mkdir()) {
                System.err.println();
                editor.statusError("ERR: no se puede crear directorio data !");
                return;
            }
        }
        MustacheFactory mf = new DefaultMustacheFactory();
        try {
            CompressorStreamFactory csf = new CompressorStreamFactory();

            for (String t : content.keySet()) {
                if (content.get(t).containsKey("source_path")) {
                    File srcPath = (File)(content.get(t).get("source_path"));
                    System.out.println(String.format("INFO: COPYING file %1$s into data/%2$s ...", srcPath.getAbsolutePath(), t));
                    FileUtils.copyFile(srcPath, new File(dataDir, t));
                } else if (content.get(t).containsKey("template_path")) {
                    File tplPath = (File)(content.get(t).get("template_path"));
                    System.out.println(String.format("INFO: PARSING file %1$s into data/%2$s.gz ...", tplPath.getAbsolutePath(), t));
                    Mustache tpl_content = mf.compile(tplPath.getAbsolutePath());

                    HashMap<String, ArrayList< HashMap<String, String> >> tpl_context = new HashMap<String, ArrayList< HashMap<String, String> >>();
                    tpl_context.put("modules", new ArrayList<HashMap<String, String>>());
                    tpl_context.put("extra_jslibs", new ArrayList<HashMap<String, String>>());
                    ArrayList<String> extra_jslibs = new ArrayList<String>();
                    for (String m : modules_enabled) {
                        HashMap<String, String> modrow = new HashMap<String, String>();
                        for (String k : modules.get(m).keySet()) {
                            if (k.startsWith("module_")) {
                                Object o = modules.get(m).get(k);
                                if (o instanceof File) {
                                    modrow.put(k, ((File)o).getAbsolutePath());
                                } else {
                                    modrow.put(k, (String)o);
                                }
                            }
                        }
                        HashMap<String, HashMap<String, Object>> templates =
                            (HashMap<String, HashMap<String, Object>>)(modules.get(m).get("templates"));
                        if (templates.containsKey(t)) {
                            for (String k : templates.get(t).keySet()) {
                                Object o = templates.get(t).get(k);
                                String v;
                                if (o instanceof File) {
                                    v = ((File)o).getAbsolutePath();
                                } else {
                                    v = (String)o;
                                }
                                if (k.equals("module_extra_jslibs")) {
                                    for (String ejl : v.split("\\s+")) {
                                        ejl = ejl.trim();
                                        if (!ejl.isEmpty() && !extra_jslibs.contains(ejl)) {
                                            extra_jslibs.add(ejl);
                                        }
                                    }
                                } else {
                                    modrow.put(k, v);
                                }
                            }
                        }

                        tpl_context.get("modules").add(modrow);
                    }
                    for (String ejl : extra_jslibs) {
                        HashMap<String, String> _e = new HashMap<String, String>();
                        _e.put("jslib", ejl);
                        tpl_context.get("extra_jslibs").add(_e);
                    }

                    // Comprimir con gzip de apache commons
                    CompressorOutputStream gzipOutStream = csf.createCompressorOutputStream(
                        CompressorStreamFactory.GZIP,
                        new FileOutputStream(new File(dataDir, t+".gz")));
                    tpl_content
                        .execute(new OutputStreamWriter(gzipOutStream, "UTF-8"), tpl_context)
                        .close();
                }
                manifest.add(t);
            }

            // TODO: Escribir manifest.txt en formato UNIX

        } catch (IOException e) {
            // Uno o más archivos no se pudieron leer o escribir
            System.err.println();
            editor.statusError(e);
            return;
        } catch (org.apache.commons.compress.compressors.CompressorException e) {
            // No se encuentra soporte para gzip. Esto no debería pasar.
            System.err.println();
            editor.statusError(e);
            return;
        }
    }

    private List<File> _buildDataTemplateDirList()
    {
        // Escanear lista de bibliotecas del sistema
        // - Localizar directorios de librerías compatibles con YUBOX Framework (contiene carpeta data-template)
        //  - Localizar directorio de YUBOX Framework (contiene el archivo yubox-framework-assemble)
        ArrayList<File> ybxLibs = new ArrayList<>();
        File ybxCoreLib = null;
        for (UserLibraryFolder lf : BaseNoGui.getLibrariesFolders()) {
            //System.err.println("DEBUG: libraries folder at "+lf.folder.getAbsolutePath());
            if (!lf.folder.exists() || !lf.folder.isDirectory()) continue;

            File[] files = lf.folder.listFiles();
            if (files.length <= 0) continue;

            for (File testLib : files) {
                File test_dataTpl = new File(testLib, "data-template");
                if (!test_dataTpl.exists() || !test_dataTpl.isDirectory()) continue;

                // En este punto test_dataTpl es una biblioteca compatible con YUBOX Framework.
                // Se verifica si es la biblioteca base de YUBOX Framework
                File test_assembleScript = new File(testLib, "yubox-framework-assemble");
                if (test_assembleScript.exists()) {
                    // Biblioteca base de YUBOX Framework
                    System.out.println("YUBOX Now library at: "+testLib.getAbsolutePath());
                    ybxCoreLib = test_dataTpl;
                } else {
                    // Biblioteca compatible con YUBOX Framework
                    System.out.println("YUBOX Now extension library at: "+testLib.getAbsolutePath());
                    ybxLibs.add(test_dataTpl);
                }
            }
        }

        // Abortar si la biblioteca base de YUBOX Framework no fue localizada
        if (ybxCoreLib == null) {
            System.err.println();
            editor.statusError("Failed to locate core YUBOX Now library folder!");
            return null;
        }
        ybxLibs.add(0, ybxCoreLib);

        // Localizar directorio del sketch actual. Dentro de este directorio
        // tiene que existir el directorio data-template/ para módulos personalizados
        File dataTplDir = new File(editor.getSketch().getFolder(), "data-template");
        if (dataTplDir.exists()) {
            ybxLibs.add(dataTplDir);
        } else {
            System.err.println("Sketch does not provide data-template directory! No custom behavior intended?");
        }

        return ybxLibs;
    }

    private void _loadAvailableModules(
        List<File> template_dirs,
        HashMap<String, HashMap<String, Object>> content,
        HashMap<String, HashMap<String, Object>> modules)
        throws IOException
    {
        for (File d : template_dirs) {
            File[] ls = d.listFiles();
            for (File filepath : ls) {
                String fn = filepath.getName();

                //System.err.println("DEBUG: " + fn);
                if (filepath.isDirectory()) {
                    // Este directorio podría ser un módulo. Se lee si tiene un module.ini
                    File module_ini = new File(filepath, "module.ini");
                    if (module_ini.exists() && module_ini.isFile()) {
                        // Hay un module.ini presente. Se lo abre para agregar configuración
                        HashMap<String, HashMap<String, String>> config = _readConfigIni(module_ini);
                        modules.computeIfAbsent(fn, k -> {
                            HashMap<String, Object> h = new HashMap<String, Object>();
                            h.put("module_name", k);
                            h.put("templates", new HashMap<String, HashMap<String, Object>>());
                            return h;
                        });
                        modules.get(fn).put("module_path", filepath);

                        // Cada sección del module.ini define un archivo final para el cual
                        // definir claves y valores
                        for (String tpl : config.keySet()) {
                            HashMap<String, HashMap<String, Object>> templates =
                                (HashMap<String, HashMap<String, Object>>)modules.get(fn).get("templates");
                            templates.computeIfAbsent(tpl, k -> {
                                HashMap<String, Object> h = new HashMap<String, Object>();
                                h.put("module_content", null);
                                return h;
                            });
                            for (String key : config.get(tpl).keySet()) {
                                templates.get(tpl).put("module_"+key, config.get(tpl).get(key));
                            }
                        }

                        // Dentro del directorio, además del module.ini hay archivos con
                        // los nombres del archivo final. Si existen, son la fuente del
                        // contenido de module_content
                        File[] lsmod = filepath.listFiles();
                        for (File fnmod : lsmod) {
                            if (fnmod.getName().equals("module.ini")) continue;
                            HashMap<String, HashMap<String, Object>> templates =
                                (HashMap<String, HashMap<String, Object>>)modules.get(fn).get("templates");
                            templates.computeIfAbsent(fnmod.getName(), k -> {
                                HashMap<String, Object> h = new HashMap<String, Object>();
                                h.put("module_content", null);
                                return h;
                            });
                            templates.get(fnmod.getName()).put("module_content_path", fnmod);
                        }
                    }
                } else if (fn.endsWith(".mustache")) {
                    // Este archivo es una plantilla. Se agrega al contenido para ser parseado.
                    String target = fn.substring(0, fn.length() - 9);   // Se quita la extensión .mustache
                    content.computeIfAbsent(target, k -> {
                        HashMap<String, Object> h = new HashMap<String, Object>();
                        h.put("target", k);
                        return h;
                    });
                    content.get(target).put("template_path", filepath);
                } else {
                    // Este archivo es un archivo a copiar directamente
                    content.computeIfAbsent(fn, k -> {
                        HashMap<String, Object> h = new HashMap<String, Object>();
                        h.put("target", k);
                        return h;
                    });
                    content.get(fn).put("source_path", filepath);
                }
            }
        }
    }

    private HashMap<String, HashMap<String, String>> _readConfigIni(File f)
        throws IOException
    {
        Pattern section = Pattern.compile("\\s*\\[([^]]*)\\]\\s*");
        Pattern keyValue = Pattern.compile("\\s*([^=]*)=(.*)");
        HashMap<String, HashMap<String, String>> conf = new HashMap<String, HashMap<String, String>>();
        BufferedReader r = new BufferedReader(new FileReader(f));

        String l; String s = null;
        while ((l = r.readLine()) != null) {
            Matcher m = section.matcher(l);
            if (m.matches()) {
                // Se ha encontrado una nueva sección
                s = m.group(1).trim();
            } else if (s != null) {
                m = keyValue.matcher(l);
                if (m.matches()) {
                    String k = m.group(1).trim();
                    String v = m.group(2).trim();
                    conf.computeIfAbsent(s, key -> new HashMap<String, String>());
                    conf.get(s).put(k, v);
                }
            }
        }

        r.close();
        return conf;
    }

    private String _loadEnabledModuleList(HashMap<String, HashMap<String, Object>> modules, ArrayList<String> modules_enabled)
        throws IOException
    {
        String module_active = null;
        File modulesTxt = new File(editor.getSketch().getFolder(), "modules.txt");
        if (!modulesTxt.exists()) {
            System.err.println();
            editor.statusError("File modules.txt does not exist. No information on modules to enable!");
            return null;
        }
        BufferedReader r = new BufferedReader(new FileReader(modulesTxt));
        String l = r.readLine().trim();
        r.close();
        String[] mods = l.split("\\s+");
        for (String mod : mods) {
            if (mod.startsWith("+")) {
                mod = mod.substring(1);
                module_active = mod;
            }
            if (!modules.containsKey(mod)) {
                System.err.println();
                editor.statusError("FATAL: module "+mod+" does not exist in module list!");
                return null;
            }
            modules_enabled.add(mod);
        }

        if (modules_enabled.isEmpty()) {
            System.err.println();
            editor.statusError("FATAL: no modules enabled for project!");
            return null;
        }
        if (module_active == null) {
            module_active = modules_enabled.get(0);
        }

        return module_active;
    }
}
