package app.moroshka.notes

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Tesseract 5 / tessdata_fast 4.1.0 (Apache-2.0).
 * Only model downloads use the network; image recognition is strictly local. */
internal class LocalOcr(context:Context) {
    private val root=File(context.filesDir,"tesseract-v1")
    private val folder=File(root,"tessdata")
    private data class Pack(val name:String,val size:Int,val hash:String)
    private val packs=listOf(
        Pack("eng",4113088,"7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
        Pack("rus",3861738,"e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"))
    private fun file(p:Pack)=File(folder,p.name+".traineddata")
    private fun valid(p:Pack):Boolean = runCatching {
        val f=file(p)
        f.length()==p.size.toLong()&&PhotoStore.hash(f.readBytes())==p.hash
    }.getOrDefault(false)
    fun ready():Boolean=packs.all(::valid)
    fun install(){
        check(folder.isDirectory||folder.mkdirs()){"Не удалось создать каталог OCR"}
        for(p in packs){
            if(valid(p))continue
            val url="https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/65727574dfcd264acbb0c3e07860e4e9e9b22185/"+p.name+".traineddata"
            val connection=URL(url).openConnection() as HttpsURLConnection
            try{
                connection.instanceFollowRedirects=false
                connection.connectTimeout=15000;connection.readTimeout=45000
                check(connection.responseCode==200){"Не удалось загрузить языковой пакет OCR"}
                val bytes=connection.inputStream.use{PhotoStore.bounded(it,p.size)}
                check(bytes.size==p.size&&PhotoStore.hash(bytes)==p.hash){"Контрольная сумма языкового пакета не совпадает"}
                val atomic=AtomicFile(file(p));val out=atomic.startWrite()
                try{out.write(bytes);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
            }finally{connection.disconnect()}
        }
        check(ready()){"Языковые пакеты не установлены"}
    }
    fun recognize(bytes:ByteArray):String {
        check(ready()){"Установите локальные языковые пакеты OCR в настройках"}
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)){"Не удалось открыть фото"}
        val tess=TessBaseAPI()
        try{
            check(tess.init(root.absolutePath,"rus+eng",TessBaseAPI.OEM_LSTM_ONLY)){"Не удалось запустить OCR"}
            tess.setImage(bitmap)
            return tess.utF8Text.orEmpty().trim().take(64000)
        }finally{tess.recycle();bitmap.recycle()}
    }
}
