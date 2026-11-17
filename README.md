# 📺 GameSub TV — Android TV APK & QR Telefon Kontrol Paneli

Bu klasör (`e:\proje\çeviri\temp\mobil`), **Android TV / Google TV / Mi Box / TV Box** cihazlarınızda oyun oynarken İngilizce altyazıları gerçek zamanlı olarak Türkçeye çeviren ve **tüm ayarlarını Android telefonunuzdan QR Kod okutarak yönetebildiğiniz** yerel Android TV uygulama projesidir.

---

## 🌟 Nasıl Çalışır?

1. **TV'ye Tek APK Kurulumu:**
   - Sadece TV'nize `GameSub-TV.apk` dosyasını kurarsınız. Telefonunuza ekstra hiçbir uygulama kurmanıza gerek yoktur!
2. **TV'de QR Kod Eşleştirme:**
   - TV'de uygulamayı açtığınızda:
     1. **"1. TV Servis İznini Aç"** butonuyla *Erişilebilirlik -> GameSub TV Canlı Altyazı Çevirici* servisini bir kez aktif edersiniz (böylece her açılışta ekran yakalama izni sormaz ve tüm oyunların üstünde şeffaf Türkçe altyazı gösterebilir).
     2. Sağ tarafta büyük bir **QR Kod** görünür.
3. **Telefonunuzdan Tam Kontrol (Canlı Ekran Görüntüsüyle Konum Seçme):**
   - Telefonunuzun kamerasıyla TV'deki QR kodu okuttuğunuz anda telefonunuzda **GameSub TV Kumandası** açılır:
     - **📸 TV Ekranını Getir:** TV'deki oyunun o anki görüntüsü telefon ekranınıza gelir!
     - **🎯 Mavi Kutu (ROI):** Parmağınızla sürükleyerek oyundaki İngilizce altyazının yerini seçersiniz.
     - **💬 Yeşil Kutu (Overlay):** Parmağınızla sürükleyerek Türkçe altyazının TV'de nerede ve ne büyüklükte çıkacağını seçersiniz.
     - **⚡ Hız Testli 6 Çeviri Motoru:** `Google Hızlı Nöral [~55ms]`, `Groq AI LLaMA-3.1-8B [~70ms]`, `Groq AI LLaMA-3.3-70B [~135ms]`, `Lingva`, `MyMemory`, `Google Gemini 2.0 Flash` motorları arasından seçim yapabilir, API anahtarınızı telefonunuzdan yapıştırabilir ve **"🏁 Tüm Motorların Hızını Ölç"** butonuyla TV üzerindeki hızlarını görebilirsiniz.
     - **💾 KAYDET VE TV'YE UYGULA:** Bastığınız anda tüm ayarlar milisaniyeler içinde TV'deki uygulamaya geçer!

---

## 🛠️ APK Dosyasını Oluşturma (2 Kolay Yöntem)

### Yöntem 1: Android Studio ile (Bilgisayarınızda Yüklüyse)
1. **Android Studio**'yu açın -> **Open** diyerek `e:\proje\çeviri\temp\mobil` klasörünü seçin.
2. Üst menüden **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** seçeneğine tıklayın.
3. Oluşan `app/build/outputs/apk/debug/app-debug.apk` dosyasını (`Send Files to TV` uygulamasıyla veya USB bellekle) Android TV'nize atıp kurun.

### Yöntem 2: Bilgisayara Hiçbir Şey Kurmadan (GitHub Actions ile Ücretsiz Bulut Derleme)
Bilgisayarınıza 10 GB'lık Android SDK kurmak istemiyorsanız, proje içine hazır [`.github/workflows/build-apk.yml`](file:///e:/proje/çeviri/temp/mobil/.github/workflows/build-apk.yml) bulut derleyicisi eklenmiştir:
1. `e:\proje\çeviri\temp\mobil` klasörünün içeriğini ücretsiz bir GitHub deposuna (repository) yükleyin.
2. GitHub'da **Actions** sekmesine tıklayın -> **Build GameSub TV APK** otomatik çalışacak ve 2 dakika içinde size hazır **`GameSub-TV-APK.zip`** çıktısını verecektir.

---

## 📱 Telefon Panelini Şimdi Bilgisayarda / Telefonda Denemek İsterseniz
TV'ye kurmadan önce telefon kontrol panelinin (`remote_panel.html`) nasıl çalıştığını hemen görmek için şu komutu çalıştırıp ekranda çıkan adresi telefonunuzdan açabilirsiniz:

```powershell
python telefon_test_sunucusu.py
```
