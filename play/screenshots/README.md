# Store screenshots

1. On a phone, sign in with the **demo staff account** (sample leads only, never real students) and take these 6 screenshots:
   1. Leads tab (list) · 2. Call screen with a lead card · 3. After-call screen · 4. Lead page · 5. Recents with lead names · 6. WhatsApp chooser
2. Put them in `raw/` as `1.png` … `6.png` and run:
```
python3 frame.py raw/1.png "Your leads for today, in one list" out/1.png
python3 frame.py raw/2.png "Know every lead before you answer" out/2.png
python3 frame.py raw/3.png "Save the result in one tap" out/3.png
python3 frame.py raw/4.png "Notes, fee, papers and admission" out/4.png
python3 frame.py raw/5.png "Lead names in recent calls" out/5.png
python3 frame.py raw/6.png "WhatsApp, Business or clone: your choice" out/6.png
```
Output: 1080 × 1920 PNG, ready for Play (2–8 phone screenshots).
