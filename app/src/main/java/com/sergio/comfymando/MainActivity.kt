package com.sergio.comfymando

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App() } } }
    }
}

object Api {
    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 120000
        return c
    }

    private fun text(c: HttpURLConnection): String {
        val code = c.responseCode
        val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw Exception("HTTP $code: ${s.take(500)}")
        return s
    }

    fun upload(base: String, bytes: ByteArray): String {
        val b = "----cm" + System.currentTimeMillis()
        val c = open("$base/upload/image", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
        c.outputStream.use { o ->
            o.write("--$b\r\nContent-Disposition: form-data; name=\"image\"; filename=\"mando_${System.currentTimeMillis()}.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            o.write(bytes)
            o.write("\r\n--$b\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n--$b--\r\n".toByteArray())
        }
        return JSONObject(text(c)).getString("name")
    }

    fun prepare(wf: String, image: String, prompt: String): JSONObject {
        val root = JSONObject(wf)
        val positive = HashSet<String>()
        for (k in root.keys()) {
            val ins = root.optJSONObject(k)?.optJSONObject("inputs") ?: continue
            val p = ins.optJSONArray("positive")
            if (p != null && p.length() > 0) positive.add(p.get(0).toString())
        }
        var textSet = false
        val encoders = ArrayList<JSONObject>()
        for (k in root.keys()) {
            val n = root.optJSONObject(k) ?: continue
            val ins = n.optJSONObject("inputs") ?: continue
            when (n.optString("class_type")) {
                "LoadImage" -> ins.put("image", image)
                "CLIPTextEncode" -> if (ins.opt("text") is String) {
                    encoders.add(ins)
                    if (k in positive && prompt.isNotBlank()) { ins.put("text", prompt); textSet = true }
                }
            }
            for (s in listOf("seed", "noise_seed")) {
                if (ins.opt(s) is Number) ins.put(s, Random.nextLong(0, 1L shl 40))
            }
        }
        if (!textSet && prompt.isNotBlank() && encoders.isNotEmpty()) encoders[0].put("text", prompt)
        return root
    }

    fun submit(base: String, wf: JSONObject): String {
        val c = open("$base/prompt", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("prompt", wf).put("client_id", "comfymando").toString().toByteArray()) }
        return JSONObject(text(c)).getString("prompt_id")
    }

    fun history(base: String, id: String): JSONObject? =
        JSONObject(text(open("$base/history/$id", "GET"))).optJSONObject(id)

    fun bestOutput(h: JSONObject): JSONObject? {
        val outs = h.optJSONObject("outputs") ?: return null
        val all = ArrayList<JSONObject>()
        for (k in outs.keys()) {
            val n = outs.getJSONObject(k)
            for (f in n.keys()) {
                val a: JSONArray = n.optJSONArray(f) ?: continue
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    if (o.has("filename") && o.optString("type") == "output") all.add(o)
                }
            }
        }
        val order = listOf("mp4", "webm", "webp", "gif")
        return all.minByOrNull { val e = it.getString("filename").substringAfterLast('.').lowercase(); order.indexOf(e).let { i -> if (i < 0) 99 else i } }
    }

    fun download(base: String, o: JSONObject): ByteArray {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val u = "$base/view?filename=${e(o.getString("filename"))}&subfolder=${e(o.optString("subfolder"))}&type=${e(o.optString("type", "output"))}"
        val c = open(u, "GET")
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode} al descargar")
        return c.inputStream.use { it.readBytes() }
    }
}

fun saveMedia(ctx: Context, name: String, bytes: ByteArray): Pair<Uri, Boolean> {
    val ext = name.substringAfterLast('.').lowercase()
    val video = ext == "mp4" || ext == "webm"
    val mime = when (ext) { "mp4" -> "video/mp4"; "webm" -> "video/webm"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/png" }
    val v = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "ComfyMando_${System.currentTimeMillis()}.$ext")
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else "Pictures") + "/ComfyMando")
    }
    val col = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri = ctx.contentResolver.insert(col, v)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    return uri to video
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cm", 0) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.getString("url", "http://192.168.1.50:8188") ?: "") }
    var workflow by remember { mutableStateOf(prefs.getString("wf", "") ?: "") }
    var prompt by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<Uri?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { image = it }
    val pickWf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            workflow = ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText()
            prefs.edit().putString("wf", workflow).apply()
            status = "Flujo cargado (${workflow.length} caracteres)"
        }
    }

    fun generate() {
        val base = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        prefs.edit().putString("url", url).putString("wf", workflow).apply()
        scope.launch {
            busy = true; video = null
            try {
                val img = image ?: throw Exception("Elige una imagen")
                if (workflow.isBlank()) throw Exception("Carga o pega un flujo en formato API")
                val result = withContext(Dispatchers.IO) {
                    status = "Subiendo imagen…"
                    val bytes = ctx.contentResolver.openInputStream(img)!!.use { it.readBytes() }
                    val name = Api.upload(base, bytes)
                    val id = Api.submit(base, Api.prepare(workflow, name, prompt))
                    status = "Generando… (puede tardar varios minutos)"
                    var out: JSONObject? = null
                    val end = System.currentTimeMillis() + 30 * 60 * 1000
                    while (out == null && System.currentTimeMillis() < end) {
                        delay(2000)
                        val h = Api.history(base, id) ?: continue
                        if (h.optJSONObject("status")?.optString("status_str") == "error") throw Exception("ComfyUI devolvió un error al generar")
                        out = Api.bestOutput(h)
                    }
                    if (out == null) throw Exception("Tiempo agotado")
                    status = "Descargando…"
                    saveMedia(ctx, out.getString("filename"), Api.download(base, out))
                }
                if (result.second) video = result.first
                status = "Listo. Guardado en la galería (carpeta ComfyMando)."
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ComfyMando", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(url, { url = it }, label = { Text("Servidor ComfyUI (IP:8188)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pickWf.launch(arrayOf("*/*")) }) { Text("Cargar flujo (.json)") }
            OutlinedButton({ pickImage.launch("image/*") }) { Text(if (image == null) "Elegir imagen" else "Imagen ✓") }
        }
        OutlinedTextField(prompt, { prompt = it }, label = { Text("Texto (opcional)") }, modifier = Modifier.fillMaxWidth())
        Button({ generate() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Trabajando…" else "Generar vídeo") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status)
        video?.let { u ->
            AndroidView(factory = { c ->
                VideoView(c).apply { setVideoURI(u); setOnPreparedListener { it.isLooping = true; start() } }
            }, modifier = Modifier.fillMaxWidth().height(360.dp))
        }
    }
}
