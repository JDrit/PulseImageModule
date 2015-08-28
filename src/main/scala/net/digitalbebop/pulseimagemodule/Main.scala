package net.digitalbebop.pulseimagemodule


import java.io.{FileInputStream, FilenameFilter, File}
import java.nio.file._
import java.nio.file.attribute.{BasicFileAttributes, FileOwnerAttributeView}
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl._

import com.google.protobuf.ByteString
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.unboundid.ldap.sdk.{SearchScope, SimpleBindRequest, LDAPConnection}
import net.digitalbebop.ClientRequests.IndexRequest
import org.apache.commons.cli.{DefaultParser, Options}
import org.apache.commons.io.filefilter.TrueFileFilter
import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.HttpClients

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.{Future, ExecutionContext}
import scala.util.parsing.json.JSONObject

class NaiveTrustManager extends X509TrustManager {
  /**
   * Doesn't throw an exception, so this is how it approves a certificate.
   * @see javax.net.ssl.X509TrustManager#checkClientTrusted(java.security.cert.X509Certificate[], String)
   **/
  def checkClientTrusted(cert: Array[X509Certificate], authType: String): Unit = { }


  /**
   * Doesn't throw an exception, so this is how it approves a certificate.
   * @see javax.net.ssl.X509TrustManager#checkServerTrusted(java.security.cert.X509Certificate[], String)
   **/
  def checkServerTrusted (cert: Array[X509Certificate], authType: String): Unit = { }

  /**
   * @see javax.net.ssl.X509TrustManager#getAcceptedIssuers()
   **/
   def getAcceptedIssuers: Array[X509Certificate] = Array()
}

object Main {

  val startTime = System.currentTimeMillis() / 1000
  final val apiServer = "http://localhost:8080"
  final val imageModule = "images"

  val filesProcessed  = new AtomicLong(0)

  implicit val ec = new ExecutionContext {
    val threadPool = Executors.newCachedThreadPool()

    def execute(runnable: Runnable): Unit = threadPool.submit(runnable)

    def reportFailure(t: Throwable) {}
  }

  lazy val getSocketFactory: SSLSocketFactory = {
    val tm: Array[TrustManager] = Array(new NaiveTrustManager())
    val context = SSLContext.getInstance("SSL")
    context.init(Array[KeyManager](), tm, new SecureRandom())
    context.getSocketFactory
  }

  def splitCamelCase(s: String): String = s.replaceAll(String.format("%s|%s|%s",
    "(?<=[A-Z])(?=[A-Z][a-z])", "(?<=[^A-Z])(?=[A-Z])", "(?<=[A-Za-z])(?=[^A-Za-z])"), " ")

  def replaceSplits(s: String) = s.replaceAll("[_|-]", " ")

  def cleanString: String => String = splitCamelCase _ compose replaceSplits

  def getAlbums(dir: File, queue: BlockingQueue[File]): Unit =
    dir.listFiles.filter(_.isDirectory).flatMap(_.listFiles).filter(_.isDirectory).foreach(queue.add)

  def processAlbum(dir: File): (String, IndexRequest) = {
    val images = dir.listFiles

    val strBuilder = new StringBuilder()
    dir.getAbsolutePath.split("/").dropWhile(_ != "albums").tail.foreach(dir =>
      strBuilder.append(" " + cleanString(dir)))
    val indexData = strBuilder.toString()
    val url = "https://gallery.csh.rit.edu/v"
    val albums = "albums"

    val path = dir.getAbsolutePath
    val moduleId = path.substring(path.indexOf(albums) + albums.length)
    val location = url + moduleId
    val meta = Map(
      "format" -> "album",
      "title" -> cleanString(dir.getName),
      "count" -> images.length
    )
    val timestamp = Files.readAttributes(dir.toPath, classOf[BasicFileAttributes]).creationTime().toMillis

    val indexBuilder = IndexRequest.newBuilder()
    indexBuilder.setIndexData(indexData)
    indexBuilder.setLocation(location)
    indexBuilder.setMetaTags(new JSONObject(meta).toString())
    indexBuilder.setModuleId(moduleId)
    indexBuilder.setModuleName(imageModule)
    indexBuilder.setTimestamp(timestamp)
    indexBuilder.addTags("album")
    (moduleId, indexBuilder.build())
  }

  def processImage(moduleId: String, file: File): IndexRequest = {
    val rawData = Files.readAllBytes(file.toPath)
    val strBuilder = new StringBuilder()
    file.getAbsolutePath.split("/").dropWhile(_ != "albums").tail.foreach(dir =>
      strBuilder.append(" " + dir.replaceAll("[_|-]", " ")))
    val indexData = strBuilder.toString()
    val url = "https://gallery.csh.rit.edu/v"
    val albums = "albums"

    val path = file.getAbsolutePath
    val location = path.substring(path.indexOf(albums) + albums.length)
    val meta = Map(
      "format" -> "image",
      "title" -> cleanString(file.getName)
    )
    val timestamp = Files.readAttributes(file.toPath, classOf[BasicFileAttributes]).creationTime().toMillis

    val indexBuilder = IndexRequest.newBuilder()
    indexBuilder.setIndexData(indexData)
    indexBuilder.setRawData(ByteString.copyFrom(rawData))
    indexBuilder.setLocation(s"$url/$location")
    indexBuilder.setModuleName(imageModule)
    indexBuilder.setMetaTags(new JSONObject(meta).toString())
    indexBuilder.setTimestamp(timestamp)
    indexBuilder.setModuleId(file.getAbsolutePath)
    indexBuilder.addTags("image")
    indexBuilder.addTags(moduleId)
    indexBuilder.build()
  }


  def postMessage(message: IndexRequest): Unit = {
    val post = new HttpPost(s"$apiServer/api/index")
    post.setEntity(new ByteArrayEntity(message.toByteArray))
    HttpClients.createDefault().execute(post).close()
    val amount = filesProcessed.incrementAndGet()
    if (amount % 10 == 0) {
      val timeDiff = System.currentTimeMillis() / 1000 - startTime
      println(s"processed $amount files, ${(1.0 * amount) / timeDiff} files/sec")
    }
  }

  def main(args: Array[String]): Unit = {

    val options = new Options()
    options.addOption("dir", true, "the directory to recursively look through")

    val parser = new DefaultParser()
    val cmd = parser.parse(options, args)

    val dir = if (cmd.hasOption("dir"))
      cmd.getOptionValue("dir")
    else
      "/"

    val queue = new ArrayBlockingQueue[File](100)
    val POISON_PILL = null

    val f =  new FutureTask[Unit](new Callable[Unit]() {
      def call(): Unit = {
        getAlbums(new File(dir), queue)
        queue.put(POISON_PILL)
      }
    })
    ec.execute(f)

    val workerCount = Runtime.getRuntime.availableProcessors() * 2
    for (i <- 1 to workerCount) {
      ec.execute(new Runnable() {
        def run() : Unit = {
          while (true) {
            val dir = queue.take()
            if (dir == POISON_PILL) {
              queue.put(POISON_PILL)
              return
            } else {
              val (moduleId, request) = processAlbum(dir)
              postMessage(request)
              dir.listFiles().map(child => processImage(moduleId, child)).foreach(postMessage)
              Thread.sleep(5 * 1000)
            }
          }
        }
      })
    }

    ec.threadPool.shutdown()
    ec.threadPool.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)

    val endTime = System.currentTimeMillis() / 1000

    println(s"Processed ${filesProcessed.get()} files in ${endTime - startTime} seconds")

  }
}
