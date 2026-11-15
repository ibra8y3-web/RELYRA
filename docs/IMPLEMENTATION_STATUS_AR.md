# حالة تنفيذ RELYRA

المصطلحات: **Implemented** = الكود موجود ومربوط بالتطبيق · **Tested** = توجد اختبارات آلية نجحت في CI أو في الفحوص المذكورة في `CHANGELOG.md` · **Verified** = تم التأكد على أجهزة أو خوادم حقيقية · **Partial** جزئي · **Planned** مخطط · **Missing** غير موجود.

لا توجد ميزة بحالة **Verified** حتى الآن: سيناريوهات المرحلة 10 (أجهزة متعددة، مكالمات، Mesh، TURN، DHT، خوادم حقيقية) لم تُشغَّل على أجهزة فعلية.

| المجال | الحالة | المتبقي |
|---|---|---|
| الهوية والمفاتيح وQR وروابط الدعوة | Implemented, Tested | تجربة على هاتفين حقيقيين |
| قاعدة البيانات المشفرة (Room وSQLCipher وKeystore) | Implemented, Tested | ملفات schema ثابتة لاختبارات الترحيل |
| X3DH وPrekeys وDouble Ratchet | Implemented, Tested | تجربة على هاتفين حقيقيين |
| محرك التسليم وMessageID وACK ومنع التكرار | Implemented, Tested | اختبارات انقطاع شبكة حقيقية |
| مجموعات MLS (OpenMLS عبر Rust/JNI) | Implemented, Partial | تنسيق المقترحات بين عدة مشرفين، وتعارض Commit وEpoch، ومعالجة Fork وReplay |
| مزامنة الأجهزة المتعددة | Partial | حالة MLS ومفاتيح الجلسات والمرفقات |
| المجتمعات | Partial | نص المنشورات غير مشفر طرفيًا على الخادم، والاكتشاف العام غير موزع |
| Nostr | Implemented | اختبار مع Relay حقيقي |
| Blossom (رفع وتنزيل مشفر، SHA-256) | Implemented, Partial | رفع مجزأ وقابل للاستكمال، واختبار مع خادم حقيقي |
| IPFS (Kubo ومرفقات مشفرة) | Implemented, Partial | دورة حياة المرفقات وإدارة المراجع |
| P2P المباشر / WebRTC | Implemented | اختبارات عدة عقد وTURN على شبكات حقيقية |
| خدمة اكتشاف LibP2P DHT (Go) | Implemented, Partial | اختبار متعدد العقد؛ ثغرة DHT المعروفة GO-2024-3218 مفتوحة وموثقة وغير مخفية؛ تطبيق Android ليس عقدة LibP2P |
| Bluetooth وBLE Mesh وWi-Fi Direct وLAN | Implemented | اختبار متعدد القفزات على أجهزة حقيقية؛ Store & Forward غير موجود (Missing) |
| Tor | Partial | يوجد فقط محدد SOCKS ووضع صارم؛ لا Tor مدمج ولا إدارة Bridges ولا اختبار تسرب |
| المكالمات (WebRTC صوت/فيديو) | Implemented | اختبارات أجهزة حقيقية وTURN |
| النسخ والاستعادة (التطبيق) | Implemented, Tested | اختبار Instrumentation على جهاز؛ أُلغي في آخر تشغيل CI |
| نسخ الخادم (Docker volumes) | Implemented, Partial | الأرشيفات غير مشفرة ما لم تشغّل `relyra backup-file encrypt`؛ غير مربوط بـ Server Studio |
| المتصفح المدمج | Implemented, Partial | أُعيدت كتابته بعد بلاغ الشاشة البيضاء؛ شريط العنوان والرجوع والتقدم والإشارات المرجعية وسجل اختياري مشفر وتنزيلات بتأكيد. علامات التبويب (حتى 8) وقواعد كل موقع لـ JavaScript وملفات الارتباط والكاميرا والميكروفون منفذة. الموقع الجغرافي والإشعارات والنوافذ المنبثقة ومسح تخزين كل موقع على حدة غير موجودة (Missing)؛ لم يُتحقق منه على جهاز بعد |
| Vault ومدير الملفات وعارض PDF وأدوات فحص الملفات | Implemented, Partial | Document Studio أوسع، ومدير أرشيف، وتحليل APK، ومتصفح أفضل |
| عميل Matrix وتشفير Rust SDK | Implemented, Partial | لا يوجد جسر بين RELYRA وMatrix؛ واختبارات مع خادم حقيقي |
| XMPP / OMEMO | Implemented, Partial | مخزن مفاتيح OMEMO غير محمي بـ Keystore؛ يجب مراجعة ترخيص مكتبة Signal (GPL-3.0) قبل التوزيع |
| Server Studio وNode Operator | Partial | أدوات companion تعمل على الكمبيوتر وليس داخل التطبيق |
| الإضافات | Implemented, Tested | إضافات بيانات فقط (إشعار ونسخ نص وفتح رابط)، وإضافات الكود مخططة (Planned) |
| Local API | Implemented, Partial | على Loopback وبرمز فقط. نقاط قراءة GET بالإضافة إلى صلاحية اختيارية `messages.send` (إرسال رسائل نصية فردية وجماعية وعرض جهات الاتصال والمجموعات)؛ إدارة المجموعات (إنشاء وإضافة وإزالة أعضاء) والمرفقات وسجل الرسائل عبر الواجهة غير موجودة (Missing). لم يُتحقق منه على جهاز بعد |
| واجهة العربية والإنجليزية | Implemented | تدقيق RTL شامل واختبار TalkBack |
| CI وSBOM وفحص الأمان | Implemented, Partial | بوابة الثغرات تبقى حمراء ما دامت ثغرة DHT مفتوحة؛ و283 نتيجة ترخيص تحتاج مراجعة بشرية |
| المعمارية | Partial | وحدة Gradle واحدة `app`؛ وملف `MainViewModel` كبير جدًا ويجب تقسيمه |
