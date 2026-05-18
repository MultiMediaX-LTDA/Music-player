package com.androidkimyona.jammer

import dev.rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class JammerShell {

    // Função interna para executar comandos usando o Rish/Shizuku
    fun executarComandoLocal(comando: String): String {
        // 1. Verifica se o Shizuku está ativo e se temos permissão
        if (!Shizuku.pingBinder()) {
            return "Erro: Serviço Shizuku não está rodando no sistema!"
        }

        try {
            // 2. Executa o comando exatamente como o Rish faz no terminal
            val process = Shizuku.newProcess(arrayOf("sh", "-c", comando), null, null)
            
            // 3. Lê a resposta do terminal local
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val resultado = StringBuilder()
            var linha: String?
            
            while (reader.readLine().also { linha = it } != null) {
                resultado.append(linha).append("\n")
            }
            
            process.waitFor()
            return resultado.toString() // Retorna o texto do terminal
            
        } catch (e: Exception) {
            return "Erro ao executar comando: ${e.message}"
        }
    }
}

