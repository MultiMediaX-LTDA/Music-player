package com.androidkimyona.jammer;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {

    // Carrega os seus motores .so do FFmpeg antes de tudo
    static {
        try {
            System.loadLibrary("avutil");
            System.loadLibrary("swresample");
            System.loadLibrary("avcodec");
            System.loadLibrary("avformat");
            System.out.println("Jammer: Motor de áudio FFmpeg carregado!");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Jammer: Erro nos .so: " + e.getMessage());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.out.println("Jammer: MainActivity inicializada!");
    }
}