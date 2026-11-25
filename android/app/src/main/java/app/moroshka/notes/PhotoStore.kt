package app.moroshka.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Immutable JPEGs, referenced by content hash from ordinary Markdown. */
internal class PhotoStore(private val context: Context) {
    private val folder = File(context.filesDir,"media-v1").apply { mkdirs() }
    companion object {
        const val maxBytes = 2 * 1024 * 1024
        const val maxSide = 2400
        private val valid = Regex("[0-9a-f]{64}")
        private val reference = Regex("!\\[Фото\\]\\(moroshka-media:([0-9a-f]{64})\\)[ \\t]*\\r?")
        fun refs(text:String):List<String> = MarkdownMetadata.proseLines(text).mapNotNull { (_,line) ->
            reference.matchEntire(line)?.groupValues?.get(1)
        }.distinct()
        fun strip(text:String):String {
            val ids=refs(text)
            if(ids.isEmpty())return text
            val prefix=ids.joinToString("\n"){"![Фото](moroshka-media:"+it+")"}+"\n\n"
            if(text.startsWith(prefix))return text.removePrefix(prefix)
            val indexes=MarkdownMetadata.proseLines(text).filter{reference.matches(it.second)}.map{it.first}.toSet()
            return text.split('\n').filterIndexed{i,_->i !in indexes}.joinToString("\n")
        }
        fun compose(text:String,ids:List<String>):String {
            require(ids.distinct().size<=128&&ids.all{valid.matches(it)})
            if(ids.isEmpty())return text
            return ids.distinct().joinToString("\n"){"![Фото](moroshka-media:"+it+")"}+"\n\n"+text
        }
        fun append(text:String,id:String):String {
            require(valid.matches(id))
            return if(id in refs(text))text else compose(strip(text),refs(text)+id)
        }
        fun preview(text:String):String {
            val body=MarkdownMetadata.proseLines(strip(text)).map{it.second.trim()}
                .filter{it.isNotEmpty()&&!Regex("^#{1,6}(?:\\s|$)").containsMatchIn(it)&&
                    !Regex("^(?:#[\\p{L}\\p{N}_-]+\\s*)+$").matches(it)}
                .joinToString(" ").replace(Regex("\\s+")," ").trim()
            val title=MarkdownMetadata.title(text)
            return body.ifBlank{if(title!="Без названия")title else if(refs(text).isNotEmpty())"Фотозаметка" else "Пустая заметка"}
        }
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun bounded(input:InputStream,limit:Int):ByteArray {
            val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true){
                val n=input.read(buffer); if(n<0)break
                require(out.size()+n<=limit){"Слишком большой файл изображения"}
                out.write(buffer,0,n)
            }
            return out.toByteArray()
        }
        fun base64(bytes:ByteArray)=Base64.encodeToString(bytes,Base64.NO_WRAP)
        fun decode(data:String):ByteArray {
            require(data.length<=((maxBytes+2)/3)*4 && data.matches(Regex("[A-Za-z0-9+/]+={0,2}"))){"Некорректное изображение"}
            val bytes=Base64.decode(data,Base64.NO_WRAP)
            require(base64(bytes)==data){"Некорректный Base64"}
            return bytes
        }
        fun verify(id:String,bytes:ByteArray){
            require(valid.matches(id)&&hash(bytes)==id){"Контрольная сумма изображения не совпадает"}
            require(bytes.size in 16..maxBytes && (bytes[0].toInt() and 255)==255 &&
                (bytes[1].toInt() and 255)==216 && (bytes[bytes.lastIndex-1].toInt() and 255)==255 &&
                (bytes.last().toInt() and 255)==217){"Ожидается JPEG до 2 МиБ"}
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outMimeType=="image/jpeg"&&options.outWidth in 1..maxSide&&options.outHeight in 1..maxSide){"Некорректный размер JPEG"}
        }
    }
    private fun file(id:String):File {
        require(valid.matches(id))
        return File(folder,id+".jpg")
    }
    fun read(id:String):ByteArray? {
        val path=file(id)
        if(!path.exists()&&!File(path.path+".bak").exists())return null
        val bytes=AtomicFile(path).openRead().use { bounded(it,maxBytes) }
        verify(id,bytes);return bytes
    }
    fun save(bytes:ByteArray,id:String=hash(bytes)):String {
        verify(id,bytes)
        if(read(id)!=null)return id
        val atomic=AtomicFile(file(id))
        val stream=atomic.startWrite()
        try{stream.write(bytes);atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
        require(read(id)!=null){"Не удалось сохранить фотографию"}
        return id
    }
    fun bitmap(id:String,limit:Int=480):Bitmap? {
        val bytes=read(id)?:return null
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>limit*2)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
    }
    fun importUri(uri:Uri):String {
        val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use { bounded(it,32*1024*1024) }
        return importBytes(bytes)
    }
    fun importBytes(bytes:ByteArray):String {
        val decoded=if(Build.VERSION.SDK_INT>=28){
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))){decoder,info,_ ->
                val w=info.size.width;val h=info.size.height
                require(w>0&&h>0)
                val scale=minOf(1.0,maxSide.toDouble()/maxOf(w,h))
                decoder.setTargetSize(maxOf(1,(w*scale).toInt()),maxOf(1,(h*scale).toInt()))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }else{
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outWidth>0&&options.outHeight>0){"Не удалось открыть изображение"}
            var sample=1
            while(maxOf(options.outWidth,options.outHeight)/sample>maxSide*2)sample*=2
            val raw=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                BitmapFactory.Options().apply{inSampleSize=sample})){"Не удалось открыть изображение"}
            val orientation=runCatching{ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
            val matrix=Matrix()
            when(orientation){
                2->matrix.setScale(-1f,1f)
                3->matrix.setRotate(180f)
                4->matrix.setScale(1f,-1f)
                5->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
                6->matrix.setRotate(90f)
                7->{matrix.setRotate(-90f);matrix.postScale(-1f,1f)}
                8->matrix.setRotate(-90f)
            }
            val oriented=Bitmap.createBitmap(raw,0,0,raw.width,raw.height,matrix,true)
            if(oriented!==raw)raw.recycle()
            oriented
        }
        val scale=minOf(1.0,maxSide.toDouble()/maxOf(decoded.width,decoded.height))
        val sized=Bitmap.createScaledBitmap(decoded,maxOf(1,(decoded.width*scale).toInt()),maxOf(1,(decoded.height*scale).toInt()),true)
        if(sized!==decoded)decoded.recycle()
        val opaque=Bitmap.createBitmap(sized.width,sized.height,Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply{drawColor(Color.WHITE);drawBitmap(sized,0f,0f,null)}
        sized.recycle()
        try{
            for(quality in listOf(90,80,65,50,35)){
                val output=ByteArrayOutputStream()
                check(opaque.compress(Bitmap.CompressFormat.JPEG,quality,output))
                val result=output.toByteArray()
                if(result.size<=maxBytes)return save(result)
            }
            error("Не удалось сжать фотографию до 2 МиБ")
        }finally{opaque.recycle()}
    }
    fun pull(text:String,api:(String,JSONObject?,String)->JSONObject,checkpoint:()->Unit={}){
        for(id in refs(text)){
            checkpoint()
            if(read(id)!=null)continue
            val response=api("/api/media/"+id,null,"GET")
            checkpoint()
            check(response.getString("id")==id)
            save(decode(response.getString("data")),id)
        }
    }
    fun push(text:String,api:(String,JSONObject?,String)->JSONObject,checkpoint:()->Unit={}){
        val ids=refs(text)
        if(ids.isEmpty())return
        require(ids.size<=128){"Не более 128 изображений в заметке"}
        checkpoint()
        val response=api("/api/media/missing",JSONObject().put("ids",JSONArray(ids)),"POST").getJSONArray("missing")
        checkpoint()
        val missing=(0 until response.length()).map{response.getString(it)}
        check(missing.distinct().size==missing.size&&missing.all{it in ids})
        for(id in missing){
            val bytes=requireNotNull(read(id)){"Не найден локальный файл фотографии"}
            checkpoint()
            val stored=api("/api/media/"+id,JSONObject().put("data",base64(bytes)),"PUT")
            checkpoint()
            check(stored.getString("id")==id)
        }
    }
}
