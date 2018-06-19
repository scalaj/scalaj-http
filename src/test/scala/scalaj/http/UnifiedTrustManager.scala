package scalaj.http

import java.io.{BufferedInputStream, FileNotFoundException, InputStream}
import java.security.KeyStore
import java.security.cert.{Certificate, CertificateException, CertificateFactory, X509Certificate}
import java.util.Arrays
import javax.net.ssl._

import scala.collection.JavaConverters._

object UnifiedTrustManager {
  def createKeyStoreFromCerts(inputStream: InputStream): KeyStore = {
    val certFactory = CertificateFactory.getInstance("X.509")
    val caInputStream = new BufferedInputStream(inputStream)
    val cas: Iterable[Certificate] = try {
      certFactory.generateCertificates(caInputStream).asScala
    } finally {
      caInputStream.close()
    }
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, null)
    cas.zipWithIndex.foreach(ca => keyStore.setCertificateEntry("customCa." + ca._2, ca._1))
    keyStore
  }

  def loadResource(resourceName: String): InputStream = {
    val is = this.getClass.getClassLoader.getResourceAsStream(resourceName)
    if (is == null) {
      throw new FileNotFoundException(s"Couldn't find resource $resourceName in classpath")
    }
    is
  }

  def loadKeyStore(resourceName: String): KeyStore = {
    createKeyStoreFromCerts(loadResource(resourceName))
  }

  def createTrustManager(resourceName: String): X509TrustManager = new UnifiedTrustManager(loadKeyStore(resourceName))
  def createTrustManager(inputStream: InputStream): X509TrustManager = {
    new UnifiedTrustManager(createKeyStoreFromCerts(inputStream))
  }

  def createSocketFactory(resourceName: String): SSLSocketFactory = {
    createSocketFactory(loadResource(resourceName))
  }
  def createSocketFactory(inputStream: InputStream): SSLSocketFactory = {
    val sc = SSLContext.getInstance("TLS")
    sc.init(null, Array[TrustManager](createTrustManager(inputStream)), new java.security.SecureRandom())
    sc.getSocketFactory
  }

  private class UnifiedTrustManager (val localKeyStore: KeyStore) extends X509TrustManager {
    val defaultTrustManager: X509TrustManager = createTrustManager(null)
    val localTrustManager: X509TrustManager = createTrustManager(localKeyStore)
    val acceptedIssuers = {
      val first: Array[X509Certificate] = defaultTrustManager.getAcceptedIssuers
      val second: Array[X509Certificate] = localTrustManager.getAcceptedIssuers
      val result : Array[X509Certificate]= Arrays.copyOf(first, first.length + second.length)
      System.arraycopy(second, 0, result, first.length, second.length)
      result
    }

    private def createTrustManager(store: KeyStore): X509TrustManager = {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(store)
      tmf.getTrustManagers()(0).asInstanceOf[X509TrustManager]
    }

    def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {
      try {
        defaultTrustManager.checkServerTrusted(chain, authType)
      } catch {
        case ce: CertificateException => localTrustManager.checkServerTrusted(chain, authType)
      }
    }

    def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {
      try {
        defaultTrustManager.checkClientTrusted(chain, authType)
      } catch {
        case ce: CertificateException => localTrustManager.checkClientTrusted(chain, authType)
      }
    }

    def getAcceptedIssuers: Array[X509Certificate] = acceptedIssuers
  }
}
