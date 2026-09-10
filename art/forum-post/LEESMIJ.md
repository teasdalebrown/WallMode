# WallMode-forumpost

- `preview.html`: leesbare preview van de volledige Engelse post.
- `post.md`: titel, bericht en afbeeldingsplaatsen voor het forum.
- `images/`: de drie PNG-afbeeldingen, genummerd in plaatsingsvolgorde.

## Voor plaatsen

De publieke broncode staat op https://github.com/rvbcrs/WallMode. Die link is
ingevuld in de post, samen met de Releases-link voor ondertekende ontwikkelbuilds.

De post legt uit hoe lezers de APK onder Assets bij de nieuwste pre-release
downloaden. Hiervoor is geen GitHub-login nodig. Dit pakket bevat zelf geen APK.
Volg voor publicatie de relevante controles in `RELEASING.md`, inclusief mediarechten.

## Op het Home Assistant-forum

Categorie: https://community.home-assistant.io/c/projects/9

1. Gebruik de eerste regel van `post.md` als topictitel, zonder het `#`.
2. Plaats de rest als berichttekst in de Markdown-editor.
3. Upload iedere PNG op de bijbehorende afbeeldingsplaats. Laat de editor de
   echte `upload://`-link invoegen; lokale `images/...`-paden werken niet op het forum.
4. Controleer de preview en beide publieke links voordat je publiceert.

## Herkomst afbeeldingen

- `01-wallmode.png`: typografische titelafbeelding met het bestaande WallMode-beeldmerk.
- `02-display-settings.png`: echte browserpaneel-HTML uit de Android-testexport,
  met de huidige gecompileerde app-stijl. Voorbeeldwaarden ingevuld; lokale
  adressen, tijdelijke formuliertokens en teststrings verwijderd. Dit is een
  interface-render, geen foto of bewijs van een live apparaatverbinding.
- `03-notification.png`: Engelstalige native Android-render uit de bestaande
  notificatietest, uitgevoerd op een emulator op ThinkSmart-schermformaat.
  De tekst is een testmelding, geen echte huishouddata.

De captions in de forumpost benoemen de voorbeelden expliciet. Er zijn geen
AI-gegenereerde appschermen of verzonnen productfuncties gebruikt.
