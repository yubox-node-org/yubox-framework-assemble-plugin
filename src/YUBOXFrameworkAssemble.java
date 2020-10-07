package com.yubox.arduinoplugin;

import java.io.File;

import processing.app.PreferencesData;
import processing.app.Editor;
import processing.app.tools.Tool;

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

        // Localizar directorio del sketch actual. Dentro de este directorio
        // tiene que existir el directorio data-template/, o no se puede continuar
        File sketchFolder = editor.getSketch().getFolder();
        File dataTplFolder = new File(sketchFolder, "data-template");
        if (!dataTplFolder.exists()) {
            editor.statusNotice("Sketch does not provide data-template directory! No custom behavior intended?");
        }

        System.err.println();
        editor.statusError("TODO: implementar realmente");
    }

}
