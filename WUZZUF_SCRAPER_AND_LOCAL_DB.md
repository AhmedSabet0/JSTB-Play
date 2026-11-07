# Wuzzuf Collector & Local Server Database (Pure Self-Hosted)

دليل شامل لمحرك سحب وظائف موقع **Wuzzuf** وقاعدة البيانات المحلية على سيرفر اللينكس، بدون الاعتماد على أي خدمات سحابية خارجية (No Supabase Dependency).

---

## 1. فلسفة المحرك وطريقة العمل (Architecture & Scraping Engine)

* **وظائف عامة (Public Jobs):** جميع وظائف موقع Wuzzuf عامة ولا تتطلب تسجيل دخول أو حسابات أو كوكيز.
* **الـ REST API الرسمي المباشر:** بدلاً من تجريف HTML الثقيل المعرض للكسر، يقوم المحرك بمخاطبة الـ Endpoints الرسمية لـ Wuzzuf مباشرة بنفس معيار `JSON:API`:
  * `Accept: application/vnd.api+json`
  * `Content-Type: application/vnd.api+json`
* **تدفق الجلب على خطوتين (Two-Step Fetching):**
  1. **الخطوة الأولى (البحث):** إرسال طلب `POST https://wuzzuf.net/api/search/job` للحصول على معرّفات الوظائف (`job UUIDs`) وأسماء الشركات وإجمالي النتائج.
  2. **الخطوة الثانية (التفاصيل):** إرسال طلب تجميعي (Batch) `GET https://wuzzuf.net/api/job?filter[other][ids]=...` لجلب كامل بيانات الوظائف (الوصف، الشروط، الراتب، سنين الخبرة، نوع العمل، المهارات).
* **معدل طلبات هادئ (Polite Rate Limiting):**
  * توقف زمني آمن (`PAUSE = 1.5s`) بين كل طلب لتجنب أي حظر للـ IP.
  * حجم الصفحة: 15 وظيفة في الطلب.

---

## 2. قاعدة البيانات المحلية على السيرفر (Local Server Database - No Supabase)

تم إلغاء الحاجة لـ Supabase بالكامل وتوجيه التخزين إلى قاعدة بيانات محلية عالية الأداء على نفس السيرفر (`SQLite` مع دعم `WAL mode` للفهرسة السريعة والقراءة المتزامنة):

* **مسار قاعدة البيانات الافتراضي:** `data/jobs.db`
* **الجداول المنشأة تلقائياً:**
  1. `wuzzuf_jobs`: جدول البيانات الكاملة للوظائف المسحوبة مع منع التكرار (Deduplication) وتحديث حقل `last_seen_at` إذا كانت الوظيفة مكررة.
  2. `opportunities`: جدول الفرص الموحد المجهز لتطبيق الموبايل (`source = 'wuzzuf_job'`, `apply_type = 'easy_apply'`).
  3. `job_queries`: جدول دورات البحث وتاريخ آخر سحب وعدد النتائج لكل مسمى.
* **تصدير فوري للملفات:** يتم تصدير أحدث 100 وظيفة تلقائياً إلى ملف `data/wuzzuf_latest.json`.

---

## 3. الكلمات المفتاحية والمسميات (LinkedIn Easy Apply Keywords)

يستخدم المحرك نفس المسميات الوظيفية الخاصة بنظام التقديم السريع (LinkedIn Easy Apply):

* **DevOps, Cloud & Infrastructure:**
  * `DevOps Engineer`, `Cloud Engineer`, `Site Reliability Engineer (SRE)`, `Platform Engineer`, `Infrastructure Engineer`, `Kubernetes Engineer`, `Systems Engineer`, `Linux Administrator`.
* **Software Engineering & Development:**
  * `Backend Developer`, `Frontend Developer`, `Full Stack Developer`, `Software Engineer`, `Python Developer`, `Java Developer`, `Node.js Developer`, `.NET Developer`, `Dotnet Developer`, `PHP Developer`, `Laravel Developer`, `Golang Developer`, `Mobile Developer`, `Flutter Developer`, `Android Developer`, `iOS Developer`, `React Developer`, `Angular Developer`.
* **Networking, NOC & Telecom:**
  * `Network Engineer`, `NOC Engineer`, `Telecom Engineer`, `Network Security Engineer`, `System Administrator`, `IT Support Engineer`.
* **Data, AI & Cybersecurity:**
  * `Data Engineer`, `Data Analyst`, `Machine Learning Engineer`, `Data Scientist`, `Cyber Security Engineer`, `SOC Analyst`.
* **QA & Design:**
  * `QA Engineer`, `Software Test Engineer`, `Automation QA Engineer`, `UI/UX Designer`, `Product Manager`.

> **ملاحظة:** يقرأ السكريبت تلقائياً من ملف `collector/titles.txt` إذا وُجد على السيرفر، أو يمكنك تحديد ملف مخصص عبر الخيار `--titles-file`.

---

## 4. كيفية التشغيل على سيرفر اللينكس (Ubuntu Linux VPS)

### الخطوة 1: الدخول للمشروع وتثبيت المتطلبات البسيطة
```bash
cd ~/autolinky
pip install requests
# اختياري: إذا أردت محاكاة Chrome TLS Fingerprint
pip install curl_cffi
```

### الخطوة 2: تشغيل دورة سحب تجريبية لمرة واحدة (--once)
```bash
python3 scripts/wuzzuf_collector.py --once
```

### الخطوة 3: تشغيل المحرك كـ Daemon في الخلفية يتكرر كل 3 ساعات
```bash
nohup python3 scripts/wuzzuf_collector.py --daemon --interval 3 > wuzzuf.log 2>&1 &
```

### الخطوة 4 (اختياري): تشغيل سيرفر الـ API المحلي لتقديم الوظائف للموبايل
السكريبت مزود بسيرفر HTTP مدمج وخفيف جداً لخدمة الوظائف على البورت 8080:
```bash
python3 scripts/wuzzuf_collector.py --daemon --interval 3 --serve --port 8080
```
يمكنك فحص الوظائف عبر المتصفح أو curl:
```bash
curl http://127.0.0.1:8080/api/jobs
```

---

## 5. إعداد المحرك كخدمة Systemd دائمة (Systemd Service)

لضمان عمل المحرك بشكل دائم حتى بعد إعادة تشغيل السيرفر (Reboot):

1. أنشئ ملف الخدمة:
```bash
sudo nano /etc/systemd/system/wuzzuf-collector.service
```

2. ضع المحتوى التالي:
```ini
[Unit]
Description=Wuzzuf Autonomous Job Scraper
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/root/autolinky
ExecStart=/usr/bin/python3 scripts/wuzzuf_collector.py --daemon --interval 3 --serve --port 8080
Restart=always
RestartSec=10
StandardOutput=append:/var/log/wuzzuf.log
StandardError=append:/var/log/wuzzuf_err.log

[Install]
WantedBy=multi-user.target
```

3. فعّل وشغّل الخدمة:
```bash
sudo systemctl daemon-reload
sudo systemctl enable wuzzuf-collector
sudo systemctl start wuzzuf-collector
sudo systemctl status wuzzuf-collector
```
