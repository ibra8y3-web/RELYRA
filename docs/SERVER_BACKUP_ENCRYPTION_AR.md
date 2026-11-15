# تشفير أرشيفات نسخ الخادم الاحتياطية

**الحالة:** منفّذ ومُختبر (اختبارات Node) · زر **Create backup** في Server Studio ما زال ينتج لقطات `.tar.gz` غير مشفرة؛ التشفير أمر منفصل تشغّله على هذه الأرشيفات.

اللقطات التي تنشئها أدوات companion تُحفظ في `.relyra-backups/` بجوار مشروع Compose. لحماية نسخة منها بكلمة سر:

```bash
export RELYRA_BACKUP_PASSPHRASE='كلمة سر طويلة لا تقل عن 12 حرفًا'
node bin/relyra.mjs backup-file encrypt .relyra-backups/<id>-<volume>.tar.gz <id>-<volume>.tar.gz.rlybk
node bin/relyra.mjs backup-file decrypt <id>-<volume>.tar.gz.rlybk restored.tar.gz
```

- تُقرأ كلمة السر من متغير البيئة فقط، وليس من وسائط سطر الأوامر.
- الصيغة: AES-256-GCM بمفتاح مشتق عبر scrypt (N=32768) مع Salt وIV جديدين لكل ملف.
- كلمة سر خاطئة أو تغيير أي بايت يُفشل فك التشفير ولا يترك ملفًا ناتجًا جزئيًا.
- لا يستبدل الأمر أي ملف موجود.
- إذا ضاعت كلمة السر فلا يمكن استرجاع الملف.

الاستعادة عبر Server Studio ما زالت تحتاج `.tar.gz` العادي داخل `.relyra-backups/` (يجب أن يطابق SHA-256 ما في الـ manifest). ربط التشفير مباشرة بالإنشاء والاستعادة يحتاج بيئة Docker للاختبار وهو **مخطط فقط**.
