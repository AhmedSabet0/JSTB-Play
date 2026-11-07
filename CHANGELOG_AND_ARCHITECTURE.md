# JSTB Mobile App - Complete Changelog & Architecture Fixes (v3.4.12)

توثيق شامل لجميع التعديلات والتحسينات المنجزة على تطبيق الموبايل (إصدار Google Play وإصدار GitHub الكامل).

---

## 1. مشكلة شريط التقدم والـ Auto-Advance Deadlock (تم الحل)

* **الأعراض السابقة:** عند اكتمال ملء الصفحة، كان التطبيق يظهر عبارة *"Fields ready. Tap Review Application to proceed"* أو *"Tap Next Step to proceed"* ويتوقف دون الضغط التلقائي على الزر.
* **السبب الجذري:**
  1. نافذة LinkedIn Easy Apply تحتوي في أعلاها على شريط تقدم الخطوات (20%, 40%, 60%, 100%)، وهذا العنصر في كود لينكد إن يحمل الـ Attribute: `<div role="progressbar">`.
  2. في كود `advance()` السابق، كان هناك فحص:
     `var isUploading = document.querySelector('.artdeco-loader, [role="progressbar"], ...');`
  3. كان الكود يعثر على شريط تقدم الخطوات ويعتقد أن هناك ملف قيد الرفع (`uploading`)، فيتوقف عن الضغط على أزرار **Next Step** أو **Review Application**!
* **الحل المنفذ:**
  * تم استبعاد `[role="progressbar"]` وحصر فحص الرفع على اللودر الحقيقي لرفع الملفات (`.artdeco-loader`, `.jobs-document-upload__loading`, `.jobs-document-upload--loading`).
  * تم توسيع البحث عن الأزرار ليشمل كافة الصفحة واستبعاد نوافذ التنبيهات المنبثقة (Dismiss / Job alerts).
  * تم تطبيق تسلسل حركة المستخدم الكامل على الأزرار:
    `pointerdown` -> `mousedown` -> `pointerup` -> `mouseup` -> `click`
    لضمان استجابة أزرار React و Web Components على لينكد إن وموقع وظف.

---

## 2. مشكلة تحديد السيرة الذاتية (Resume Dual-Selection & "A resume is required") (تم الحل)

* **الأعراض السابقة:** ظهور إطارين أخضرين على كارتي الـ CV الموجودين في صفحة Resume، وظهور خطأ أحمر من لينكد إن: *"A resume is required"*.
* **السبب الجذري:**
  1. كروت الـ CV في لينكد إن موبايل كانت تُقرأ عبر `collect()` كخيارات أسئلة منفصلة، فيقوم محرك الملء باختيار النسختين معاً ووضع إطار أخضر على كارتي الـ CV.
  2. التحديد المزدوج أربك لينكد إن وألغى اختيار السيرة الذاتية الأساسية.
  3. كان شرط `has_cv` في كود Java يفحص `chars >= 150` (عدد الحروف النصية المقروءة)، فإذا كان الـ CV مصمماً كـ PDF بصور أو نصوص جرافيك، كان التطبيق يعتبر أنه لا يوجد CV مسجل ولا يقدمه للمتصفح.
* **الحل المنفذ:**
  * تم بناء دالة فحص دقيقة `isResumeElement` / `isResumeRadio` تعزل كروت وقوائم الـ CV وتمنع إدراجها ضمن الأسئلة العامة نهائياً.
  * دالة `ensureResumeSelected()` تقوم برمجياً باختيار **أول نسخة فقط (الأحدث)** وتلغي تحديد أي نسخ سابقة دون إحداث أي تضارب.
  * إذا لم تكن هناك أي سيرة ذاتية مسجلة على لينكد إن، يتم الضغط تلقائياً على زر `Upload resume` وتمرير ملف الـ PDF المسجل في التطبيق مباشرة عبر `WebChromeClient`.
  * تم تعديل شرط `has_cv` ليعتمد على وجود الملف الفعلي على ديسك الهاتف `cvFile.isFile() && cvFile.length() > 0` بدلاً من فحص عدد الحروف النصية.

---

## 3. إخفاء لوحة المفاتيح التلقائي (Auto Keyboard Dismissal) (تم الحل)

* **الأعراض السابقة:** بقاء لوحة المفاتيح (Soft Keyboard) مفتوحة بعد ملء الحقول وحجبها لنصف الشاشة وزر المتابعة.
* **الحل المنفذ:**
  * إضافة كود استدعاء `InputMethodManager.hideSoftInputFromWindow` فور اكتمال الملء في `fillDone()` وقبل الانتقال للخطوة التالية في `MainActivity.java`.
  * إضافة استدعاء `document.activeElement.blur()` في نهاية دالة `apply()` في JavaScript.

---

## 4. دعم الملء التلقائي والتقديم لوظائف موقع "وظف" (Wuzzuf Easy Apply)

* **التعرف على زر التقديم:** دعم زر *"Apply for Job"* و *"تقدم للوظيفة"* و *"قدم الآن"* عبر Regex وأنماط CSS المحددة لموقع Wuzzuf.
* **أسئلة التصفية (Screening Questions):**
  * الراتب المتوقع والحالي: إدخال أرقام صحيحة ونظيفة بالجنيه المصري (EGP).
  * سنين الخبرة: حساب دقيق لعدد سنوات الخبرة من واقع الـ Profile والسيرة الذاتية.
  * فترة الإشعار (Notice Period): اختيار فوري أو شهر حسب المعطيات.
  * مستوى اللغة الإنجليزية والتعليم والمدينة والمحافظة باللغتين العربية والإنجليزية.

---

## 5. ميزات تجربة المستخدم المضافة في v3.4.11 و v3.4.12

* **منتقي حسابات جوجل الأصلي (Native Account Picker):** استخدام `AccountManager.newChooseAccountIntent()` لاختيار حساب جوجل المسجل على الهاتف مباشرة داخل التطبيق دون الخروج لمتصفح خارجي.
* **إشعار منبثق أنيق للأسئلة المجهولة (Floating Question Pill):** شريط عائم راقٍ يظهر عند الحاجة لإدخال إجابة سؤال جديد، وبمجرد الضغط عليه ينتقل التركيز فوراً للحقل المطلوب.
* **رفع صورة البروفايل تلقائياً:** دعم الاستجابة لطلبات رفع صور المرشحين الشخصية عبر `FilesProvider.uriForPhoto()`.

---

## 6. نظام سحب وظائف Wuzzuf وقاعدة البيانات المحلية على السيرفر (Linux VPS + Local SQLite)

* **إلغاء الاعتماد على Supabase (No Cloud Dependency):**
  * تم بناء نظام تخزين محلي بالكامل على السيرفر عبر SQLite مع تفعيل `WAL mode` لتحقيق أقصى سرعة قراءة وكتابة متزامنة.
  * مسار قاعدة البيانات المحلي: `~/data/jobs.db`.
  * الجداول المنشأة:
    1. `wuzzuf_jobs`: لحفظ البيانات الكاملة لكل وظيفة مسحوبة وتحديث تاريخ الظهور `last_seen_at`.
    2. `opportunities`: جدول الفرص الموحد المجهز لتطبيق الموبايل (`source = 'wuzzuf_job'`, `apply_type = 'easy_apply'`).
    3. `job_queries`: جدول دورات البحث وتاريخ آخر سحب وعدد النتائج لكل مسمى.
  * تصدير فوري لآخر 100 وظيفة إلى ملف `~/data/wuzzuf_latest.json`.

* **مخاطبة الـ REST API الرسمي لـ Wuzzuf (Two-Step Fetching):**
  * بدلاً من كشط الـ HTML المعرض للتوقف، يخاطب السكريبت واجهات Wuzzuf الرسمية بمعيار `application/vnd.api+json`.
  * **الخطوة الأولى:** إرسال `POST https://wuzzuf.net/api/search/job` مع معلمات `startIndex`, `pageSize: 15`, `longitude: "0"`, `latitude: "0"`, `sortBy: "date"` للحصول على معرفات الوظائف وأسماء الشركات.
  * **الخطوة الثانية:** إرسال `GET https://wuzzuf.net/api/job?filter[other][ids]=...` بنظام الدفعات (Batch) لجلب كافة تفاصيل الوظائف وشروطها.
  * معدل طلبات هادئ وآمن (`PAUSE = 1.5s`) لحماية السيرفر من الحظر.

* **العمل المستقل بدون أي مكتبات خارجية (Zero Dependencies on Linux):**
  * يدعم المحرك مكتبات بايثون القياسية (`urllib.request` و `sqlite3`) تلقائياً، ويعمل على أي نظام Linux/Ubuntu نقي دون الحاجة لتثبيت `pip3` أو أي حزم إضافية.
  * مزود بسيرفر HTTP مدمج وخفيف جداً لخدمة الوظائف عبر المنفذ 8080: `http://<SERVER_IP>:8080/api/jobs`.

* **نظام الكلمات المفتاحية والمسميات (46 مسمى مطابق لـ LinkedIn Easy Apply):**
  * يشمل مجالات: DevOps, Cloud, Backend, Frontend, Full Stack, Flutter, Android, iOS, Network, NOC, Telecom, Data Engineer, Machine Learning, QA, Product Manager.
  * إمكانية قراءة مسميات مخصصة من ملف `titles.txt` على السيرفر أو تمريرها عبر خيار `--titles`.

---

## 7. أوامر تشغيل السيرفر والمحرك على الـ VPS

```bash
# 1. إيقاف أي عملية سابقة
pkill -f wuzzuf_collector.py || true

# 2. تنزيل أحدث نسخة من السكريبت
curl -fsSL https://raw.githubusercontent.com/AhmedSabet0/JSTB/main/wuzzuf_collector.py -o ~/wuzzuf_collector.py

# 3. تشغيل دورة سحب تجريبية للتأكد
python3 ~/wuzzuf_collector.py --once

# 4. تشغيل الساحب كـ Daemon في الخلفية يتكرر كل 3 ساعات ويفعل الـ API على البورت 8080
nohup python3 ~/wuzzuf_collector.py --daemon --interval 3 --serve --port 8080 > ~/wuzzuf.log 2>&1 &

# 5. متابعة السجل وفحص الـ API
tail -f ~/wuzzuf.log
curl http://127.0.0.1:8080/api/jobs
```

