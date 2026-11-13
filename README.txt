Share Hub (Android)
1. Make a new GitHub repository, upload everything from this folder (keep the folder structure,
   including the hidden .github folder).
2. Actions tab -> "Build APK" -> open the latest run -> Artifacts -> ShareHub-APK -> download and unzip.
3. Install app-debug.apk on the phone (allow "install unknown apps" once).
4. Open Share Hub -> Scan QR on your PC software -> done.

NOTE: the PC software's QR link can be https with a self-signed certificate (Image Fixer: live camera). Share Hub accepts that certificate
only for the saved software's own local-network address (192.168.x.x / 10.x.x.x / 172.16-31.x.x), in the page, in the name lookup and in
downloads. The live camera preview also needs "mediaPlaybackRequiresUserGesture = false" (set).
