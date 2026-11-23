// Appteka TV — traductions de l'interface
// Le texte source est le français. Chaque entrée : [en, es, de, it, pt, ru, tr, ar].
// Les textes sont traduits à l'affichage (MutationObserver) : le code de rendu reste en français.
// {0}, {1}… = parties variables (noms, nombres) recopiées telles quelles.

window.TvI18n = (function () {
    const LANGS = [
        ['fr', 'Français'], ['en', 'English'], ['es', 'Español'], ['de', 'Deutsch'], ['it', 'Italiano'],
        ['pt', 'Português'], ['ru', 'Русский'], ['tr', 'Türkçe'], ['ar', 'العربية'],
    ];
    const ORDER = ['en', 'es', 'de', 'it', 'pt', 'ru', 'tr', 'ar'];

    const D = {
        // ---------------------------------------------------------------- en-tête, damier, Store
        "Catégories de recherche": ["Search categories", "Categorías de búsqueda", "Suchkategorien", "Categorie di ricerca", "Categorias de pesquisa", "Категории поиска", "Arama kategorileri", "فئات البحث"],
        "Rechercher une application…": ["Search for an app…", "Buscar una aplicación…", "App suchen…", "Cerca un'app…", "Pesquisar um app…", "Поиск приложения…", "Uygulama ara…", "ابحث عن تطبيق…"],
        "Catégories affichées": ["Displayed categories", "Categorías mostradas", "Angezeigte Kategorien", "Categorie mostrate", "Categorias exibidas", "Показываемые категории", "Gösterilen kategoriler", "الفئات المعروضة"],
        "Réglages": ["Settings", "Ajustes", "Einstellungen", "Impostazioni", "Configurações", "Настройки", "Ayarlar", "الإعدادات"],
        "Quitter": ["Exit", "Salir", "Beenden", "Esci", "Sair", "Выход", "Çıkış", "خروج"],
        "Catégories": ["Categories", "Categorías", "Kategorien", "Categorie", "Categorias", "Категории", "Kategoriler", "الفئات"],
        "OK : afficher / masquer · Retour : valider": ["OK: show / hide · Back: confirm", "OK: mostrar / ocultar · Atrás: confirmar", "OK: ein-/ausblenden · Zurück: bestätigen", "OK: mostra / nascondi · Indietro: conferma", "OK: mostrar / ocultar · Voltar: confirmar", "OK: показать / скрыть · Назад: готово", "OK: göster / gizle · Geri: onayla", "OK: إظهار / إخفاء · رجوع: تأكيد"],
        "Profil Appteka": ["Appteka profile", "Perfil de Appteka", "Appteka-Profil", "Profilo Appteka", "Perfil Appteka", "Профиль Appteka", "Appteka profili", "ملف Appteka"],
        "Numéro du profil dont tu veux voir les applications (ex. 325988 pour appteka.store/profile/325988). Laisse vide pour retirer.": ["Number of the profile whose apps you want to see (e.g. 325988 for appteka.store/profile/325988). Leave empty to remove.", "Número del perfil cuyas apps quieres ver (p. ej. 325988 para appteka.store/profile/325988). Déjalo vacío para quitarlo.", "Nummer des Profils, dessen Apps du sehen willst (z. B. 325988 für appteka.store/profile/325988). Leer lassen zum Entfernen.", "Numero del profilo di cui vuoi vedere le app (es. 325988 per appteka.store/profile/325988). Lascia vuoto per rimuovere.", "Número do perfil cujos apps você quer ver (ex.: 325988 para appteka.store/profile/325988). Deixe vazio para remover.", "Номер профиля, приложения которого нужно показать (напр. 325988 для appteka.store/profile/325988). Оставьте пустым, чтобы убрать.", "Uygulamalarını görmek istediğin profil numarası (ör. appteka.store/profile/325988 için 325988). Kaldırmak için boş bırak.", "رقم الملف الذي تريد عرض تطبيقاته (مثلاً 325988 لـ appteka.store/profile/325988). اتركه فارغاً للإزالة."],
        "N° de profil": ["Profile no.", "N.º de perfil", "Profil-Nr.", "N. profilo", "N.º do perfil", "№ профиля", "Profil no.", "رقم الملف"],
        "Enregistrer": ["Save", "Guardar", "Speichern", "Salva", "Salvar", "Сохранить", "Kaydet", "حفظ"],
        "Chargement…": ["Loading…", "Cargando…", "Wird geladen…", "Caricamento…", "Carregando…", "Загрузка…", "Yükleniyor…", "جارٍ التحميل…"],
        "ARM 64 bits": ["ARM 64-bit", "ARM 64 bits", "ARM 64 Bit", "ARM 64 bit", "ARM 64 bits", "ARM 64 бит", "ARM 64 bit", "ARM ‏64 بت"],
        "ARM 32 bits": ["ARM 32-bit", "ARM 32 bits", "ARM 32 Bit", "ARM 32 bit", "ARM 32 bits", "ARM 32 бит", "ARM 32 bit", "ARM ‏32 بت"],
        "ARM (ancien)": ["ARM (legacy)", "ARM (antiguo)", "ARM (alt)", "ARM (vecchio)", "ARM (antigo)", "ARM (старый)", "ARM (eski)", "ARM (قديم)"],
        "x86 64 bits": ["x86 64-bit", "x86 64 bits", "x86 64 Bit", "x86 64 bit", "x86 64 bits", "x86 64 бит", "x86 64 bit", "x86 ‏64 بت"],
        "Universelle": ["Universal", "Universal", "Universell", "Universale", "Universal", "Универсальная", "Evrensel", "عالمية"],
        "Toutes les catégories": ["All categories", "Todas las categorías", "Alle Kategorien", "Tutte le categorie", "Todas as categorias", "Все категории", "Tüm kategoriler", "كل الفئات"],
        "Favoris": ["Favorites", "Favoritos", "Favoriten", "Preferiti", "Favoritos", "Избранное", "Favoriler", "المفضلة"],
        "Catégorie": ["Category", "Categoría", "Kategorie", "Categoria", "Categoria", "Категория", "Kategori", "فئة"],
        "Aucune catégorie affichée. Sélectionne + en haut à droite pour en choisir.": ["No category shown. Select + at the top right to choose some.", "No hay categorías. Selecciona + arriba a la derecha para elegirlas.", "Keine Kategorie angezeigt. Wähle + oben rechts, um welche auszuwählen.", "Nessuna categoria mostrata. Seleziona + in alto a destra per sceglierle.", "Nenhuma categoria exibida. Selecione + no canto superior direito para escolher.", "Нет категорий. Нажмите + вверху справа, чтобы выбрать.", "Gösterilen kategori yok. Seçmek için sağ üstteki +'ya bas.", "لا توجد فئات معروضة. اختر + أعلى اليمين لاختيارها."],
        "Erreur réseau": ["Network error", "Error de red", "Netzwerkfehler", "Errore di rete", "Erro de rede", "Ошибка сети", "Ağ hatası", "خطأ في الشبكة"],
        "Aucune application": ["No apps", "Ninguna aplicación", "Keine Apps", "Nessuna app", "Nenhum app", "Нет приложений", "Uygulama yok", "لا توجد تطبيقات"],
        "Aucun favori. Dans une fiche, choisis « Favori » (ou touche jaune sur une carte).": ["No favorites. On an app page, choose “Favorite” (or the yellow key on a card).", "Sin favoritos. En la ficha de una app, elige «Favorito» (o la tecla amarilla en una tarjeta).", "Keine Favoriten. Wähle auf einer App-Seite „Favorit“ (oder die gelbe Taste auf einer Karte).", "Nessun preferito. Nella scheda di un'app scegli «Preferito» (o il tasto giallo su una scheda).", "Nenhum favorito. Na página do app, escolha “Favorito” (ou a tecla amarela num cartão).", "Нет избранного. На странице приложения выберите «Избранное» (или жёлтую кнопку на карточке).", "Favori yok. Uygulama sayfasında «Favori»yi seç (veya kartta sarı tuş).", "لا توجد مفضلة. في صفحة التطبيق اختر «مفضلة» (أو الزر الأصفر على البطاقة)."],
        "▲ ▼ : déplacer la rangée · OK ou Retour : terminer": ["▲ ▼: move the row · OK or Back: done", "▲ ▼: mover la fila · OK o Atrás: terminar", "▲ ▼: Zeile verschieben · OK oder Zurück: fertig", "▲ ▼: sposta la riga · OK o Indietro: fine", "▲ ▼: mover a linha · OK ou Voltar: concluir", "▲ ▼: переместить ряд · OK или Назад: готово", "▲ ▼: satırı taşı · OK veya Geri: bitir", "▲ ▼: نقل الصف · OK أو رجوع: إنهاء"],
        "La rangée de recherche reste en haut": ["The search row stays at the top", "La fila de búsqueda queda arriba", "Die Suchzeile bleibt oben", "La riga di ricerca resta in alto", "A linha de pesquisa fica no topo", "Ряд поиска остаётся наверху", "Arama satırı üstte kalır", "يبقى صف البحث في الأعلى"],
        "Recherche dans toutes les catégories": ["Searching all categories", "Buscando en todas las categorías", "Suche in allen Kategorien", "Ricerca in tutte le categorie", "Pesquisando em todas as categorias", "Поиск во всех категориях", "Tüm kategorilerde arama", "البحث في كل الفئات"],
        "OK : saisir un profil": ["OK: enter a profile", "OK: introducir un perfil", "OK: Profil eingeben", "OK: inserisci un profilo", "OK: inserir um perfil", "OK: ввести профиль", "OK: profil gir", "OK: أدخل ملفاً"],
        "Chargement des catégories…": ["Loading categories…", "Cargando categorías…", "Kategorien werden geladen…", "Caricamento categorie…", "Carregando categorias…", "Загрузка категорий…", "Kategoriler yükleniyor…", "جارٍ تحميل الفئات…"],
        "Rechercher dans": ["Search in", "Buscar en", "Suchen in", "Cerca in", "Pesquisar em", "Искать в", "Şurada ara", "البحث في"],
        "OK : inclure / exclure de la recherche · Retour : valider": ["OK: include / exclude from search · Back: confirm", "OK: incluir / excluir de la búsqueda · Atrás: confirmar", "OK: in Suche ein-/ausschließen · Zurück: bestätigen", "OK: includi / escludi dalla ricerca · Indietro: conferma", "OK: incluir / excluir da pesquisa · Voltar: confirmar", "OK: включить / исключить из поиска · Назад: готово", "OK: aramaya dahil et / çıkar · Geri: onayla", "OK: تضمين / استبعاد من البحث · رجوع: تأكيد"],
        "Numéro de profil invalide": ["Invalid profile number", "Número de perfil no válido", "Ungültige Profilnummer", "Numero di profilo non valido", "Número de perfil inválido", "Неверный номер профиля", "Geçersiz profil numarası", "رقم ملف غير صالح"],
        "Profil Store retiré": ["Store profile removed", "Perfil Store quitado", "Store-Profil entfernt", "Profilo Store rimosso", "Perfil Store removido", "Профиль Store удалён", "Store profili kaldırıldı", "تمت إزالة ملف المتجر"],

        // ---------------------------------------------------------------- réglages
        "Couleur du skin": ["Skin color", "Color del tema", "Skin-Farbe", "Colore della skin", "Cor do tema", "Цвет темы", "Tema rengi", "لون السمة"],
        "Vert": ["Green", "Verde", "Grün", "Verde", "Verde", "Зелёный", "Yeşil", "أخضر"],
        "Or": ["Gold", "Oro", "Gold", "Oro", "Dourado", "Золото", "Altın", "ذهبي"],
        "Bleu": ["Blue", "Azul", "Blau", "Blu", "Azul", "Синий", "Mavi", "أزرق"],
        "Violet": ["Purple", "Violeta", "Violett", "Viola", "Roxo", "Фиолетовый", "Mor", "بنفسجي"],
        "Corail": ["Coral", "Coral", "Koralle", "Corallo", "Coral", "Коралловый", "Mercan", "مرجاني"],
        "Retour pour fermer": ["Back to close", "Atrás para cerrar", "Zurück zum Schließen", "Indietro per chiudere", "Voltar para fechar", "Назад — закрыть", "Kapatmak için Geri", "رجوع للإغلاق"],
        "Compte Appteka": ["Appteka account", "Cuenta de Appteka", "Appteka-Konto", "Account Appteka", "Conta Appteka", "Аккаунт Appteka", "Appteka hesabı", "حساب Appteka"],
        "Apparence": ["Appearance", "Apariencia", "Darstellung", "Aspetto", "Aparência", "Оформление", "Görünüm", "المظهر"],
        "Noir et or": ["Black & gold", "Negro y oro", "Schwarz & Gold", "Nero e oro", "Preto e dourado", "Чёрный и золотой", "Siyah ve altın", "أسود وذهبي"],
        "Néumorphisme clair": ["Light neumorphism", "Neumorfismo claro", "Heller Neumorphismus", "Neumorfismo chiaro", "Neumorfismo claro", "Светлый неоморфизм", "Açık nöomorfizm", "نيومورفيزم فاتح"],
        "Néumorphisme vert": ["Green neumorphism", "Neumorfismo verde", "Grüner Neumorphismus", "Neumorfismo verde", "Neumorfismo verde", "Зелёный неоморфизм", "Yeşil nöomorfizm", "نيومورفيزم أخضر"],
        "Couleur d’accent": ["Accent color", "Color de acento", "Akzentfarbe", "Colore d'accento", "Cor de destaque", "Акцентный цвет", "Vurgu rengi", "لون التمييز"],
        "Affichage": ["Display", "Pantalla", "Anzeige", "Visualizzazione", "Exibição", "Отображение", "Görüntü", "العرض"],
        "Taille des cartes": ["Card size", "Tamaño de las tarjetas", "Kartengröße", "Dimensione delle schede", "Tamanho dos cartões", "Размер карточек", "Kart boyutu", "حجم البطاقات"],
        "Marges de l'écran": ["Screen margins", "Márgenes de pantalla", "Bildschirmränder", "Margini dello schermo", "Margens da tela", "Поля экрана", "Ekran kenar boşlukları", "هوامش الشاشة"],
        "Baisse jusqu'à 0 si l'image paraît trop étroite ; monte si les bords sont coupés par ta TV.": ["Lower to 0 if the picture looks too narrow; raise it if your TV cuts the edges.", "Bájalo a 0 si la imagen parece estrecha; súbelo si tu TV corta los bordes.", "Auf 0 senken, wenn das Bild zu schmal wirkt; erhöhen, wenn dein TV die Ränder abschneidet.", "Abbassa a 0 se l'immagine sembra stretta; alza se la TV taglia i bordi.", "Diminua até 0 se a imagem parecer estreita; aumente se a TV cortar as bordas.", "Уменьшите до 0, если картинка узкая; увеличьте, если ТВ обрезает края.", "Görüntü dar görünüyorsa 0'a indir; TV kenarları kesiyorsa artır.", "اخفضها إلى 0 إذا بدت الصورة ضيقة؛ ارفعها إذا كان التلفاز يقص الحواف."],
        "Compatibilité APK": ["APK compatibility", "Compatibilidad de APK", "APK-Kompatibilität", "Compatibilità APK", "Compatibilidade de APK", "Совместимость APK", "APK uyumluluğu", "توافق APK"],
        "cette TV": ["this TV", "esta TV", "dieser TV", "questa TV", "esta TV", "этот ТВ", "bu TV", "هذا التلفاز"],
        "↺ Cette TV": ["↺ This TV", "↺ Esta TV", "↺ Dieser TV", "↺ Questa TV", "↺ Esta TV", "↺ Этот ТВ", "↺ Bu TV", "↺ هذا التلفاز"],
        "Télécharger la configuration": ["Download configuration", "Descargar la configuración", "Konfiguration herunterladen", "Scarica la configurazione", "Baixar a configuração", "Скачать конфигурацию", "Yapılandırmayı indir", "تنزيل الإعدادات"],
        "Langue": ["Language", "Idioma", "Sprache", "Lingua", "Idioma", "Язык", "Dil", "اللغة"],
        "Automatique (langue de la TV)": ["Automatic (TV language)", "Automático (idioma de la TV)", "Automatisch (TV-Sprache)", "Automatica (lingua della TV)", "Automático (idioma da TV)", "Автоматически (язык ТВ)", "Otomatik (TV dili)", "تلقائي (لغة التلفاز)"],
        "L'interface et le contenu Appteka (catégories, descriptions) suivent la langue choisie.": ["The interface and Appteka content (categories, descriptions) follow the chosen language.", "La interfaz y el contenido de Appteka (categorías, descripciones) siguen el idioma elegido.", "Oberfläche und Appteka-Inhalte (Kategorien, Beschreibungen) folgen der gewählten Sprache.", "Interfaccia e contenuti Appteka (categorie, descrizioni) seguono la lingua scelta.", "A interface e o conteúdo da Appteka (categorias, descrições) seguem o idioma escolhido.", "Интерфейс и контент Appteka (категории, описания) будут на выбранном языке.", "Arayüz ve Appteka içeriği (kategoriler, açıklamalar) seçilen dili izler.", "تتبع الواجهة ومحتوى Appteka (الفئات، الأوصاف) اللغة المختارة."],
        "Chargement du profil…": ["Loading profile…", "Cargando perfil…", "Profil wird geladen…", "Caricamento profilo…", "Carregando perfil…", "Загрузка профиля…", "Profil yükleniyor…", "جارٍ تحميل الملف…"],
        "Invité": ["Guest", "Invitado", "Gast", "Ospite", "Convidado", "Гость", "Misafir", "ضيف"],
        "Compte enregistré": ["Registered account", "Cuenta registrada", "Registriertes Konto", "Account registrato", "Conta registrada", "Зарегистрированный аккаунт", "Kayıtlı hesap", "حساب مسجّل"],
        "Compte invité (non connecté)": ["Guest account (not signed in)", "Cuenta de invitado (sin sesión)", "Gastkonto (nicht angemeldet)", "Account ospite (non connesso)", "Conta de convidado (sem login)", "Гостевой аккаунт (без входа)", "Misafir hesap (giriş yapılmadı)", "حساب ضيف (غير مسجّل الدخول)"],
        "apps publiées": ["published apps", "apps publicadas", "veröffentlichte Apps", "app pubblicate", "apps publicados", "опубликовано", "yayınlanan", "تطبيقات منشورة"],
        "favoris": ["favorites", "favoritos", "Favoriten", "preferiti", "favoritos", "избранное", "favori", "المفضلة"],
        "téléchargements": ["downloads", "descargas", "Downloads", "download", "downloads", "загрузки", "indirme", "التنزيلات"],
        "avis": ["reviews", "reseñas", "Bewertungen", "recensioni", "avaliações", "отзывы", "yorum", "المراجعات"],
        "Mon Store": ["My Store", "Mi Store", "Mein Store", "Il mio Store", "Minha Store", "Мой Store", "Mağazam", "متجري"],
        "Afficher mon Store sur l'accueil": ["Show my Store on the home screen", "Mostrar mi Store en el inicio", "Meinen Store auf dem Startbildschirm zeigen", "Mostra il mio Store nella home", "Mostrar minha Store no início", "Показать мой Store на главной", "Mağazamı ana ekranda göster", "عرض متجري في الرئيسية"],
        "Profil indisponible (réseau ?).": ["Profile unavailable (network?).", "Perfil no disponible (¿red?).", "Profil nicht verfügbar (Netzwerk?).", "Profilo non disponibile (rete?).", "Perfil indisponível (rede?).", "Профиль недоступен (сеть?).", "Profil kullanılamıyor (ağ?).", "الملف غير متاح (الشبكة؟)."],
        "Se connecter à un autre compte": ["Sign in to another account", "Iniciar sesión con otra cuenta", "Mit anderem Konto anmelden", "Accedi con un altro account", "Entrar com outra conta", "Войти в другой аккаунт", "Başka hesapla giriş yap", "تسجيل الدخول بحساب آخر"],
        "Code reçu par e-mail": ["Code received by email", "Código recibido por correo", "Per E-Mail erhaltener Code", "Codice ricevuto via e-mail", "Código recebido por e-mail", "Код из письма", "E-postayla gelen kod", "الرمز المستلم بالبريد"],
        "Ton pseudo (nouveau compte)": ["Your username (new account)", "Tu apodo (cuenta nueva)", "Dein Nutzername (neues Konto)", "Il tuo nickname (nuovo account)", "Seu apelido (conta nova)", "Ваш ник (новый аккаунт)", "Kullanıcı adın (yeni hesap)", "اسم المستخدم (حساب جديد)"],
        "Valider": ["Confirm", "Confirmar", "Bestätigen", "Conferma", "Confirmar", "Подтвердить", "Onayla", "تأكيد"],
        "Annuler": ["Cancel", "Cancelar", "Abbrechen", "Annulla", "Cancelar", "Отмена", "İptal", "إلغاء"],
        "Connexion par e-mail : un code de connexion t'est envoyé.": ["Sign in by email: a login code will be sent to you.", "Inicio de sesión por correo: te enviaremos un código.", "Anmeldung per E-Mail: Du erhältst einen Anmeldecode.", "Accesso via e-mail: ti verrà inviato un codice.", "Login por e-mail: você receberá um código.", "Вход по e-mail: вам придёт код для входа.", "E-postayla giriş: sana bir giriş kodu gönderilir.", "تسجيل الدخول بالبريد: سيُرسل إليك رمز دخول."],
        "Adresse e-mail": ["Email address", "Correo electrónico", "E-Mail-Adresse", "Indirizzo e-mail", "Endereço de e-mail", "Адрес e-mail", "E-posta adresi", "البريد الإلكتروني"],
        "Envoi…": ["Sending…", "Enviando…", "Wird gesendet…", "Invio…", "Enviando…", "Отправка…", "Gönderiliyor…", "جارٍ الإرسال…"],
        "Recevoir le code": ["Get the code", "Recibir el código", "Code anfordern", "Ricevi il codice", "Receber o código", "Получить код", "Kodu al", "استلام الرمز"],
        "Ton Store est ajouté à l'accueil.": ["Your Store was added to the home screen.", "Tu Store se añadió al inicio.", "Dein Store wurde zum Startbildschirm hinzugefügt.", "Il tuo Store è stato aggiunto alla home.", "Sua Store foi adicionada ao início.", "Ваш Store добавлен на главную.", "Mağazan ana ekrana eklendi.", "أضيف متجرك إلى الرئيسية."],
        "Adresse e-mail invalide": ["Invalid email address", "Correo no válido", "Ungültige E-Mail-Adresse", "Indirizzo e-mail non valido", "E-mail inválido", "Неверный e-mail", "Geçersiz e-posta", "بريد غير صالح"],
        "Saisis le code reçu": ["Enter the code you received", "Introduce el código recibido", "Gib den erhaltenen Code ein", "Inserisci il codice ricevuto", "Digite o código recebido", "Введите полученный код", "Gelen kodu gir", "أدخل الرمز المستلم"],
        "Choisis un pseudo": ["Choose a username", "Elige un apodo", "Wähle einen Nutzernamen", "Scegli un nickname", "Escolha um apelido", "Выберите ник", "Bir kullanıcı adı seç", "اختر اسم مستخدم"],
        "Vérification…": ["Checking…", "Verificando…", "Wird geprüft…", "Verifica…", "Verificando…", "Проверка…", "Doğrulanıyor…", "جارٍ التحقق…"],
        "Connecté ✔": ["Signed in ✔", "Sesión iniciada ✔", "Angemeldet ✔", "Connesso ✔", "Conectado ✔", "Вход выполнен ✔", "Giriş yapıldı ✔", "تم تسجيل الدخول ✔"],
        "Erreur": ["Error", "Error", "Fehler", "Errore", "Erro", "Ошибка", "Hata", "خطأ"],
        "Compte trouvé, code envoyé.": ["Account found, code sent.", "Cuenta encontrada, código enviado.", "Konto gefunden, Code gesendet.", "Account trovato, codice inviato.", "Conta encontrada, código enviado.", "Аккаунт найден, код отправлен.", "Hesap bulundu, kod gönderildi.", "تم العثور على الحساب وإرسال الرمز."],
        "Nouveau compte : choisis aussi un pseudo.": ["New account: also choose a username.", "Cuenta nueva: elige también un apodo.", "Neues Konto: wähle auch einen Nutzernamen.", "Nuovo account: scegli anche un nickname.", "Conta nova: escolha também um apelido.", "Новый аккаунт: выберите также ник.", "Yeni hesap: bir kullanıcı adı da seç.", "حساب جديد: اختر أيضاً اسم مستخدم."],
        "Code ou adresse refusé": ["Code or address rejected", "Código o dirección rechazados", "Code oder Adresse abgelehnt", "Codice o indirizzo rifiutato", "Código ou endereço recusado", "Код или адрес отклонён", "Kod veya adres reddedildi", "تم رفض الرمز أو العنوان"],
        "Trop de tentatives, réessaie plus tard": ["Too many attempts, try again later", "Demasiados intentos, inténtalo más tarde", "Zu viele Versuche, versuche es später", "Troppi tentativi, riprova più tardi", "Muitas tentativas, tente mais tarde", "Слишком много попыток, попробуйте позже", "Çok fazla deneme, sonra tekrar dene", "محاولات كثيرة، أعد المحاولة لاحقاً"],
        "Profil indisponible": ["Profile unavailable", "Perfil no disponible", "Profil nicht verfügbar", "Profilo non disponibile", "Perfil indisponível", "Профиль недоступен", "Profil kullanılamıyor", "الملف غير متاح"],

        // ---------------------------------------------------------------- fiche d'une app
        "Chargement de la fiche…": ["Loading app page…", "Cargando la ficha…", "App-Seite wird geladen…", "Caricamento scheda…", "Carregando a página…", "Загрузка страницы…", "Uygulama sayfası yükleniyor…", "جارٍ تحميل الصفحة…"],
        "✔ Développeur officiel": ["✔ Official developer", "✔ Desarrollador oficial", "✔ Offizieller Entwickler", "✔ Sviluppatore ufficiale", "✔ Desenvolvedor oficial", "✔ Официальный разработчик", "✔ Resmi geliştirici", "✔ مطوّر رسمي"],
        "Open source": ["Open source", "Código abierto", "Open Source", "Open source", "Código aberto", "Открытый код", "Açık kaynak", "مفتوح المصدر"],
        "Exclusif Appteka": ["Appteka exclusive", "Exclusivo de Appteka", "Appteka-exklusiv", "Esclusiva Appteka", "Exclusivo Appteka", "Эксклюзив Appteka", "Appteka'ya özel", "حصري على Appteka"],
        "🛡 Analyse : sûre": ["🛡 Scan: safe", "🛡 Análisis: segura", "🛡 Prüfung: sicher", "🛡 Analisi: sicura", "🛡 Análise: segura", "🛡 Проверка: безопасно", "🛡 Tarama: güvenli", "🛡 الفحص: آمن"],
        "⚠ Analyse : suspecte": ["⚠ Scan: suspicious", "⚠ Análisis: sospechosa", "⚠ Prüfung: verdächtig", "⚠ Analisi: sospetta", "⚠ Análise: suspeita", "⚠ Проверка: подозрительно", "⚠ Tarama: şüpheli", "⚠ الفحص: مشبوه"],
        "⛔ Analyse : malveillante": ["⛔ Scan: malicious", "⛔ Análisis: maliciosa", "⛔ Prüfung: schädlich", "⛔ Analisi: dannosa", "⛔ Análise: maliciosa", "⛔ Проверка: вредоносно", "⛔ Tarama: kötü amaçlı", "⛔ الفحص: ضار"],
        "🛡 Analyse en cours": ["🛡 Scan in progress", "🛡 Análisis en curso", "🛡 Prüfung läuft", "🛡 Analisi in corso", "🛡 Análise em andamento", "🛡 Идёт проверка", "🛡 Tarama sürüyor", "🛡 الفحص جارٍ"],
        "taille": ["size", "tamaño", "Größe", "dimensione", "tamanho", "размер", "boyut", "الحجم"],
        "Android minimum": ["Minimum Android", "Android mínimo", "Mindest-Android", "Android minimo", "Android mínimo", "Мин. Android", "En düşük Android", "أدنى Android"],
        "Nouveautés": ["What's new", "Novedades", "Neuigkeiten", "Novità", "Novidades", "Что нового", "Yenilikler", "الجديد"],
        "Description": ["Description", "Descripción", "Beschreibung", "Descrizione", "Descrição", "Описание", "Açıklama", "الوصف"],
        "Captures d'écran": ["Screenshots", "Capturas de pantalla", "Screenshots", "Screenshot", "Capturas de tela", "Скриншоты", "Ekran görüntüleri", "لقطات الشاشة"],
        "Avis": ["Reviews", "Reseñas", "Bewertungen", "Recensioni", "Avaliações", "Отзывы", "Yorumlar", "المراجعات"],
        "Informations": ["Information", "Información", "Informationen", "Informazioni", "Informações", "Информация", "Bilgiler", "معلومات"],
        "Paquet": ["Package", "Paquete", "Paket", "Pacchetto", "Pacote", "Пакет", "Paket", "الحزمة"],
        "Version": ["Version", "Versión", "Version", "Versione", "Versão", "Версия", "Sürüm", "الإصدار"],
        "Architectures": ["Architectures", "Arquitecturas", "Architekturen", "Architetture", "Arquiteturas", "Архитектуры", "Mimariler", "المعماريات"],
        "Langues": ["Languages", "Idiomas", "Sprachen", "Lingue", "Idiomas", "Языки", "Diller", "اللغات"],
        "Mise en ligne": ["Uploaded", "Publicado", "Hochgeladen", "Pubblicato", "Publicado", "Загружено", "Yüklenme", "تاريخ الرفع"],
        "Code source": ["Source code", "Código fuente", "Quellcode", "Codice sorgente", "Código-fonte", "Исходный код", "Kaynak kod", "الشيفرة المصدرية"],
        "Permissions": ["Permissions", "Permisos", "Berechtigungen", "Autorizzazioni", "Permissões", "Разрешения", "İzinler", "الأذونات"],
        "Apps similaires": ["Similar apps", "Apps similares", "Ähnliche Apps", "App simili", "Apps semelhantes", "Похожие приложения", "Benzer uygulamalar", "تطبيقات مشابهة"],
        "⏳ Résumé en cours de génération… il s'affichera tout seul.": ["⏳ Summary being generated… it will appear by itself.", "⏳ Generando el resumen… aparecerá solo.", "⏳ Zusammenfassung wird erstellt… sie erscheint von selbst.", "⏳ Riepilogo in generazione… apparirà da solo.", "⏳ Gerando o resumo… ele aparecerá sozinho.", "⏳ Сводка создаётся… она появится сама.", "⏳ Özet oluşturuluyor… kendiliğinden görünecek.", "⏳ جارٍ إنشاء الملخص… سيظهر تلقائياً."],
        "Aucun résumé pour l'instant.": ["No summary yet.", "Aún no hay resumen.", "Noch keine Zusammenfassung.", "Ancora nessun riepilogo.", "Ainda sem resumo.", "Сводки пока нет.", "Henüz özet yok.", "لا يوجد ملخص بعد."],
        "✨ Générer le résumé IA": ["✨ Generate AI summary", "✨ Generar resumen con IA", "✨ KI-Zusammenfassung erstellen", "✨ Genera riepilogo IA", "✨ Gerar resumo com IA", "✨ Создать ИИ-сводку", "✨ YZ özeti oluştur", "✨ إنشاء ملخص بالذكاء الاصطناعي"],
        "Résumé IA": ["AI summary", "Resumen IA", "KI-Zusammenfassung", "Riepilogo IA", "Resumo IA", "ИИ-сводка", "YZ özeti", "ملخص الذكاء الاصطناعي"],
        "Masquer la description ▴": ["Hide description ▴", "Ocultar descripción ▴", "Beschreibung ausblenden ▴", "Nascondi descrizione ▴", "Ocultar descrição ▴", "Скрыть описание ▴", "Açıklamayı gizle ▴", "إخفاء الوصف ▴"],
        "Description ▾": ["Description ▾", "Descripción ▾", "Beschreibung ▾", "Descrizione ▾", "Descrição ▾", "Описание ▾", "Açıklama ▾", "الوصف ▾"],
        "↓ Plus récentes en haut": ["↓ Newest first", "↓ Más recientes primero", "↓ Neueste zuerst", "↓ Più recenti in alto", "↓ Mais recentes primeiro", "↓ Сначала новые", "↓ En yeniler üstte", "↓ الأحدث أولاً"],
        "✓ Compatibles seulement": ["✓ Compatible only", "✓ Solo compatibles", "✓ Nur kompatible", "✓ Solo compatibili", "✓ Somente compatíveis", "✓ Только совместимые", "✓ Yalnızca uyumlu", "✓ المتوافقة فقط"],
        "Vérification des architectures…": ["Checking architectures…", "Comprobando arquitecturas…", "Architekturen werden geprüft…", "Verifica delle architetture…", "Verificando arquiteturas…", "Проверка архитектур…", "Mimariler kontrol ediliyor…", "جارٍ التحقق من المعماريات…"],
        "Confirmez l'installation à l'écran": ["Confirm the installation on screen", "Confirma la instalación en pantalla", "Bestätige die Installation am Bildschirm", "Conferma l'installazione sullo schermo", "Confirme a instalação na tela", "Подтвердите установку на экране", "Kurulumu ekranda onayla", "أكّد التثبيت على الشاشة"],
        "Installation…": ["Installing…", "Instalando…", "Wird installiert…", "Installazione…", "Instalando…", "Установка…", "Kuruluyor…", "جارٍ التثبيت…"],
        "Démarrage du téléchargement…": ["Starting download…", "Iniciando la descarga…", "Download wird gestartet…", "Avvio del download…", "Iniciando o download…", "Начало загрузки…", "İndirme başlıyor…", "بدء التنزيل…"],
        "Réessayer l'installation": ["Retry installation", "Reintentar la instalación", "Installation wiederholen", "Riprova l'installazione", "Tentar instalar de novo", "Повторить установку", "Kurulumu yeniden dene", "إعادة محاولة التثبيت"],
        "Installer": ["Install", "Instalar", "Installieren", "Installa", "Instalar", "Установить", "Yükle", "تثبيت"],
        "Mettre à jour": ["Update", "Actualizar", "Aktualisieren", "Aggiorna", "Atualizar", "Обновить", "Güncelle", "تحديث"],
        "Ouvrir": ["Open", "Abrir", "Öffnen", "Apri", "Abrir", "Открыть", "Aç", "فتح"],
        "Désinstaller": ["Uninstall", "Desinstalar", "Deinstallieren", "Disinstalla", "Desinstalar", "Удалить", "Kaldır", "إلغاء التثبيت"],
        "Installer quand même": ["Install anyway", "Instalar de todos modos", "Trotzdem installieren", "Installa comunque", "Instalar mesmo assim", "Всё равно установить", "Yine de yükle", "التثبيت على أي حال"],
        "Le téléchargement a échoué.": ["The download failed.", "La descarga falló.", "Der Download ist fehlgeschlagen.", "Il download non è riuscito.", "O download falhou.", "Загрузка не удалась.", "İndirme başarısız oldu.", "فشل التنزيل."],
        "⛔ L'analyse a détecté un logiciel malveillant. Appuie encore pour installer quand même.": ["⛔ The scan detected malware. Press again to install anyway.", "⛔ El análisis detectó malware. Pulsa otra vez para instalar de todos modos.", "⛔ Die Prüfung hat Schadsoftware gefunden. Erneut drücken, um trotzdem zu installieren.", "⛔ L'analisi ha rilevato un malware. Premi di nuovo per installare comunque.", "⛔ A análise detectou malware. Pressione de novo para instalar mesmo assim.", "⛔ Обнаружено вредоносное ПО. Нажмите ещё раз, чтобы всё равно установить.", "⛔ Taramada kötü amaçlı yazılım bulundu. Yine de yüklemek için tekrar bas.", "⛔ اكتشف الفحص برنامجاً ضاراً. اضغط مجدداً للتثبيت على أي حال."],
        "⚠ L'analyse juge cette app suspecte. Appuie encore pour installer quand même.": ["⚠ The scan flags this app as suspicious. Press again to install anyway.", "⚠ El análisis considera sospechosa esta app. Pulsa otra vez para instalarla de todos modos.", "⚠ Die Prüfung stuft diese App als verdächtig ein. Erneut drücken, um trotzdem zu installieren.", "⚠ L'analisi considera sospetta questa app. Premi di nuovo per installare comunque.", "⚠ A análise considera este app suspeito. Pressione de novo para instalar mesmo assim.", "⚠ Проверка считает приложение подозрительным. Нажмите ещё раз, чтобы всё равно установить.", "⚠ Tarama bu uygulamayı şüpheli buluyor. Yine de yüklemek için tekrar bas.", "⚠ يعتبر الفحص هذا التطبيق مشبوهاً. اضغط مجدداً للتثبيت على أي حال."],
        "Store de l'auteur": ["Author's Store", "Store del autor", "Store des Autors", "Store dell'autore", "Store do autor", "Store автора", "Yazarın mağazası", "متجر المؤلف"],
        "☆ Favori": ["☆ Favorite", "☆ Favorito", "☆ Favorit", "☆ Preferito", "☆ Favorito", "☆ В избранное", "☆ Favori", "☆ مفضلة"],
        "★ Favori": ["★ Favorite", "★ Favorito", "★ Favorit", "★ Preferito", "★ Favorito", "★ В избранном", "★ Favori", "★ مفضلة"],
        "Impossible de charger la fiche.": ["Unable to load the app page.", "No se pudo cargar la ficha.", "App-Seite konnte nicht geladen werden.", "Impossibile caricare la scheda.", "Não foi possível carregar a página.", "Не удалось загрузить страницу.", "Uygulama sayfası yüklenemedi.", "تعذّر تحميل الصفحة."],
        "Retour pour revenir.": ["Press Back to return.", "Pulsa Atrás para volver.", "Zurück drücken, um zurückzukehren.", "Premi Indietro per tornare.", "Pressione Voltar para retornar.", "Нажмите Назад, чтобы вернуться.", "Dönmek için Geri'ye bas.", "اضغط رجوع للعودة."],
        "Afficher sur l'accueil": ["Show on home screen", "Mostrar en el inicio", "Auf dem Startbildschirm zeigen", "Mostra nella home", "Mostrar no início", "Показать на главной", "Ana ekranda göster", "عرض في الرئيسية"],
        "Ajouté à l'accueil ✔": ["Added to home screen ✔", "Añadido al inicio ✔", "Zum Startbildschirm hinzugefügt ✔", "Aggiunto alla home ✔", "Adicionado ao início ✔", "Добавлено на главную ✔", "Ana ekrana eklendi ✔", "أضيف إلى الرئيسية ✔"],
        "Configuration enregistrée": ["Configuration saved", "Configuración guardada", "Konfiguration gespeichert", "Configurazione salvata", "Configuração salva", "Конфигурация сохранена", "Yapılandırma kaydedildi", "تم حفظ الإعدادات"],
        "Autorisation de stockage refusée": ["Storage permission denied", "Permiso de almacenamiento denegado", "Speicherberechtigung verweigert", "Autorizzazione di archiviazione negata", "Permissão de armazenamento negada", "Нет разрешения на доступ к памяти", "Depolama izni reddedildi", "تم رفض إذن التخزين"],
        "Résumé IA indisponible (connexion au compte requise ?)": ["AI summary unavailable (account sign-in required?)", "Resumen IA no disponible (¿requiere iniciar sesión?)", "KI-Zusammenfassung nicht verfügbar (Anmeldung nötig?)", "Riepilogo IA non disponibile (serve l'accesso?)", "Resumo IA indisponível (login necessário?)", "ИИ-сводка недоступна (нужен вход в аккаунт?)", "YZ özeti kullanılamıyor (giriş gerekli mi?)", "ملخص الذكاء الاصطناعي غير متاح (هل يلزم تسجيل الدخول؟)"],
        "Préparation du téléchargement…": ["Preparing download…", "Preparando la descarga…", "Download wird vorbereitet…", "Preparazione del download…", "Preparando o download…", "Подготовка загрузки…", "İndirme hazırlanıyor…", "تحضير التنزيل…"],
        "Impossible de démarrer le téléchargement": ["Unable to start the download", "No se pudo iniciar la descarga", "Download konnte nicht gestartet werden", "Impossibile avviare il download", "Não foi possível iniciar o download", "Не удалось начать загрузку", "İndirme başlatılamadı", "تعذّر بدء التنزيل"],
        "Fichier introuvable, réessaie l'installation": ["File not found, retry the installation", "Archivo no encontrado, reintenta la instalación", "Datei nicht gefunden, Installation wiederholen", "File non trovato, riprova l'installazione", "Arquivo não encontrado, tente instalar de novo", "Файл не найден, повторите установку", "Dosya bulunamadı, kurulumu yeniden dene", "الملف غير موجود، أعد محاولة التثبيت"],
        "Cette app ne peut pas être ouverte directement": ["This app can't be opened directly", "Esta app no se puede abrir directamente", "Diese App kann nicht direkt geöffnet werden", "Questa app non può essere aperta direttamente", "Este app não pode ser aberto diretamente", "Это приложение нельзя открыть напрямую", "Bu uygulama doğrudan açılamaz", "لا يمكن فتح هذا التطبيق مباشرة"],
        "Désinstallation impossible": ["Unable to uninstall", "No se pudo desinstalar", "Deinstallation nicht möglich", "Impossibile disinstallare", "Não foi possível desinstalar", "Не удалось удалить", "Kaldırılamadı", "تعذّر إلغاء التثبيت"],
        "Impossible d'ouvrir l'app": ["Unable to open the app", "No se pudo abrir la app", "App konnte nicht geöffnet werden", "Impossibile aprire l'app", "Não foi possível abrir o app", "Не удалось открыть приложение", "Uygulama açılamadı", "تعذّر فتح التطبيق"],
        "Sélecteur de fichiers indisponible sur cette TV": ["File picker unavailable on this TV", "Selector de archivos no disponible en esta TV", "Dateiauswahl auf diesem TV nicht verfügbar", "Selettore di file non disponibile su questa TV", "Seletor de arquivos indisponível nesta TV", "Выбор файлов недоступен на этом ТВ", "Bu TV'de dosya seçici yok", "منتقي الملفات غير متاح على هذا التلفاز"],
        "Impossible d’enregistrer la configuration": ["Unable to save the configuration", "No se pudo guardar la configuración", "Konfiguration konnte nicht gespeichert werden", "Impossibile salvare la configurazione", "Não foi possível salvar a configuração", "Не удалось сохранить конфигурацию", "Yapılandırma kaydedilemedi", "تعذّر حفظ الإعدادات"],
        "Liaison portable": ["Phone link", "Enlace con el móvil", "Handy-Verbindung", "Collegamento telefono", "Ligação ao celular", "Связь с телефоном", "Telefon bağlantısı", "ربط الهاتف"],
        "Dans Appteka sur ton portable, ouvre une app puis « Envoyer à la TV ». La TV est trouvée toute seule sur le Wi-Fi ; un code s'affiche ici la première fois.": ["In Appteka on your phone, open an app then “Send to TV”. The TV is found automatically on the Wi-Fi; a code appears here the first time.", "En Appteka del móvil, abre una app y pulsa «Enviar a la TV». La TV se detecta sola en el Wi-Fi; la primera vez aparece aquí un código.", "Öffne in Appteka auf dem Handy eine App und wähle „An TV senden“. Der TV wird im WLAN automatisch gefunden; beim ersten Mal erscheint hier ein Code.", "In Appteka sul telefono apri un'app e scegli «Invia alla TV». La TV viene trovata da sola sul Wi-Fi; la prima volta qui compare un codice.", "No Appteka do celular, abra um app e toque em “Enviar para a TV”. A TV é encontrada sozinha no Wi-Fi; na primeira vez um código aparece aqui.", "В Appteka на телефоне откройте приложение и нажмите «Отправить на ТВ». ТВ найдётся в Wi-Fi сам; в первый раз здесь появится код.", "Telefondaki Appteka'da bir uygulama aç ve «TV'ye gönder»e dokun. TV Wi-Fi'da kendiliğinden bulunur; ilk seferde burada bir kod görünür.", "في Appteka على هاتفك افتح تطبيقاً ثم «إرسال إلى التلفاز». يُعثر على التلفاز تلقائياً عبر Wi-Fi؛ يظهر هنا رمز في المرة الأولى."],
        "Appareils associés": ["Paired devices", "Dispositivos vinculados", "Gekoppelte Geräte", "Dispositivi associati", "Dispositivos pareados", "Связанные устройства", "Eşlenmiş cihazlar", "الأجهزة المقترنة"],
        "Tout oublier": ["Forget all", "Olvidar todo", "Alle vergessen", "Dimentica tutto", "Esquecer tudo", "Забыть все", "Tümünü unut", "نسيان الكل"],
        "Oublier les appareils": ["Forget devices", "Olvidar dispositivos", "Geräte vergessen", "Dimentica i dispositivi", "Esquecer dispositivos", "Забыть устройства", "Cihazları unut", "نسيان الأجهزة"],
        "Plus de fonctions": ["More features", "Más funciones", "Weitere Funktionen", "Altre funzioni", "Mais funções", "Другие функции", "Diğer özellikler", "مزيد من الميزات"],
        "Ces écrans s'ouvrent encore dans l'interface d'origine, en attendant leur version HTML.": ["These screens still open in the original interface until their HTML version is ready.", "Estas pantallas aún se abren en la interfaz original, hasta tener su versión HTML.", "Diese Bildschirme öffnen sich noch in der ursprünglichen Oberfläche, bis ihre HTML-Version fertig ist.", "Queste schermate si aprono ancora nell'interfaccia originale, in attesa della versione HTML.", "Estas telas ainda abrem na interface original, até ganharem versão HTML.", "Эти экраны пока открываются в исходном интерфейсе, до появления HTML-версии.", "Bu ekranlar HTML sürümleri hazır olana kadar özgün arayüzde açılır.", "تُفتح هذه الشاشات في الواجهة الأصلية إلى حين توفر نسختها بـ HTML."],
        "Publier un APK": ["Publish an APK", "Publicar un APK", "APK veröffentlichen", "Pubblica un APK", "Publicar um APK", "Опубликовать APK", "APK yayınla", "نشر APK"],
        "Mes publications": ["My uploads", "Mis publicaciones", "Meine Uploads", "Le mie pubblicazioni", "Minhas publicações", "Мои публикации", "Yüklemelerim", "منشوراتي"],
        "Mes téléchargements": ["My downloads", "Mis descargas", "Meine Downloads", "I miei download", "Meus downloads", "Мои загрузки", "İndirdiklerim", "تنزيلاتي"],
        "Apps installées": ["Installed apps", "Apps instaladas", "Installierte Apps", "App installate", "Apps instalados", "Установленные", "Yüklü uygulamalar", "التطبيقات المثبّتة"],
        "Favoris du compte": ["Account favorites", "Favoritos de la cuenta", "Konto-Favoriten", "Preferiti dell'account", "Favoritos da conta", "Избранное аккаунта", "Hesap favorileri", "مفضلة الحساب"],
        "Mes avis": ["My reviews", "Mis reseñas", "Meine Bewertungen", "Le mie recensioni", "Minhas avaliações", "Мои отзывы", "Yorumlarım", "مراجعاتي"],
        "Fil d’actualité": ["News feed", "Noticias", "Neuigkeiten", "Notizie", "Feed de notícias", "Лента", "Akış", "آخر الأخبار"],
        "Mon profil": ["My profile", "Mi perfil", "Mein Profil", "Il mio profilo", "Meu perfil", "Мой профиль", "Profilim", "ملفي"],
        "Discussions et accueil classique": ["Discussions & classic home", "Conversaciones e inicio clásico", "Diskussionen & klassischer Start", "Discussioni e home classica", "Discussões e início clássico", "Обсуждения и классическая главная", "Sohbetler ve klasik ana sayfa", "النقاشات والرئيسية الكلاسيكية"],
        "Modération": ["Moderation", "Moderación", "Moderation", "Moderazione", "Moderação", "Модерация", "Moderasyon", "الإشراف"],
        "Réglages avancés": ["Advanced settings", "Ajustes avanzados", "Erweiterte Einstellungen", "Impostazioni avanzate", "Configurações avançadas", "Доп. настройки", "Gelişmiş ayarlar", "إعدادات متقدمة"],
        "À propos": ["About", "Acerca de", "Über", "Informazioni", "Sobre", "О приложении", "Hakkında", "حول"],
        "Associer un portable": ["Pair a phone", "Vincular un móvil", "Handy koppeln", "Associa un telefono", "Parear um celular", "Связать телефон", "Telefon eşle", "إقران هاتف"],
        "Saisis ce code dans Appteka sur le portable. Retour pour refuser.": ["Enter this code in Appteka on the phone. Back to refuse.", "Introduce este código en Appteka del móvil. Atrás para rechazar.", "Gib diesen Code in Appteka auf dem Handy ein. Zurück zum Ablehnen.", "Inserisci questo codice in Appteka sul telefono. Indietro per rifiutare.", "Digite este código no Appteka do celular. Voltar para recusar.", "Введите этот код в Appteka на телефоне. Назад — отказать.", "Bu kodu telefondaki Appteka'ya gir. Reddetmek için Geri.", "أدخل هذا الرمز في Appteka على الهاتف. رجوع للرفض."],
        "📺 Envoyer à la TV": ["📺 Send to TV", "📺 Enviar a la TV", "📺 An TV senden", "📺 Invia alla TV", "📺 Enviar para a TV", "📺 На ТВ", "📺 TV'ye gönder", "📺 إرسال إلى التلفاز"],
        "Tous les avis et la discussion": ["All reviews and discussion", "Todas las reseñas y la conversación", "Alle Bewertungen und Diskussion", "Tutte le recensioni e la discussione", "Todas as avaliações e a discussão", "Все отзывы и обсуждение", "Tüm yorumlar ve sohbet", "كل المراجعات والنقاش"],
        "Écran indisponible": ["Screen unavailable", "Pantalla no disponible", "Bildschirm nicht verfügbar", "Schermata non disponibile", "Tela indisponível", "Экран недоступен", "Ekran kullanılamıyor", "الشاشة غير متاحة"],
        "Fermer": ["Close", "Cerrar", "Schließen", "Chiudi", "Fechar", "Закрыть", "Kapat", "إغلاق"],
        "Retour": ["Back", "Atrás", "Zurück", "Indietro", "Voltar", "Назад", "Geri", "رجوع"],
    };

    // Textes avec parties variables : modèle français -> traductions (même ordre que ORDER)
    const P = [
        ["Ajouté aux favoris : {0} — active « Favoris » avec + pour afficher la rangée", ["Added to favorites: {0} — enable “Favorites” with + to show the row", "Añadido a favoritos: {0} — activa «Favoritos» con + para ver la fila", "Zu Favoriten hinzugefügt: {0} — aktiviere „Favoriten“ mit +, um die Zeile zu zeigen", "Aggiunto ai preferiti: {0} — attiva «Preferiti» con + per vedere la riga", "Adicionado aos favoritos: {0} — ative “Favoritos” com + para ver a linha", "Добавлено в избранное: {0} — включите «Избранное» через +, чтобы увидеть ряд", "Favorilere eklendi: {0} — satırı görmek için + ile «Favoriler»i aç", "أضيف إلى المفضلة: {0} — فعّل «المفضلة» عبر + لإظهار الصف"]],
        ["Ajouté aux favoris : {0}", ["Added to favorites: {0}", "Añadido a favoritos: {0}", "Zu Favoriten hinzugefügt: {0}", "Aggiunto ai preferiti: {0}", "Adicionado aos favoritos: {0}", "Добавлено в избранное: {0}", "Favorilere eklendi: {0}", "أضيف إلى المفضلة: {0}"]],
        ["Retiré des favoris : {0}", ["Removed from favorites: {0}", "Quitado de favoritos: {0}", "Aus Favoriten entfernt: {0}", "Rimosso dai preferiti: {0}", "Removido dos favoritos: {0}", "Удалено из избранного: {0}", "Favorilerden çıkarıldı: {0}", "أزيل من المفضلة: {0}"]],
        ["Téléchargement lancé : {0} — installation automatique à la fin", ["Download started: {0} — installs automatically when done", "Descarga iniciada: {0} — se instalará al terminar", "Download gestartet: {0} — wird danach automatisch installiert", "Download avviato: {0} — installazione automatica al termine", "Download iniciado: {0} — instala automaticamente no fim", "Загрузка начата: {0} — установка начнётся автоматически", "İndirme başladı: {0} — bitince otomatik kurulur", "بدأ التنزيل: {0} — سيُثبّت تلقائياً عند الانتهاء"]],
        ["Téléchargement : {0}…", ["Downloading: {0}…", "Descargando: {0}…", "Download: {0}…", "Download: {0}…", "Baixando: {0}…", "Загрузка: {0}…", "İndiriliyor: {0}…", "جارٍ التنزيل: {0}…"]],
        ["Téléchargement {0} %", ["Downloading {0}%", "Descargando {0} %", "Download {0} %", "Download {0}%", "Baixando {0}%", "Загрузка {0} %", "İndiriliyor %{0}", "التنزيل {0}٪"]],
        ["Préparation de l'installation {0} %", ["Preparing installation {0}%", "Preparando la instalación {0} %", "Installation wird vorbereitet {0} %", "Preparazione installazione {0}%", "Preparando a instalação {0}%", "Подготовка установки {0} %", "Kurulum hazırlanıyor %{0}", "تحضير التثبيت {0}٪"]],
        ["Aucun résultat dans les catégories choisies ({0} ailleurs : bouton filtre à gauche de la recherche).", ["No results in the chosen categories ({0} elsewhere: filter button left of the search).", "Sin resultados en las categorías elegidas ({0} en otras: botón de filtro a la izquierda de la búsqueda).", "Keine Ergebnisse in den gewählten Kategorien ({0} anderswo: Filtertaste links neben der Suche).", "Nessun risultato nelle categorie scelte ({0} altrove: pulsante filtro a sinistra della ricerca).", "Nenhum resultado nas categorias escolhidas ({0} em outras: botão de filtro à esquerda da pesquisa).", "Нет результатов в выбранных категориях ({0} в других: кнопка фильтра слева от поиска).", "Seçilen kategorilerde sonuç yok (başka yerde {0}: aramanın solundaki filtre düğmesi).", "لا نتائج في الفئات المختارة ({0} في غيرها: زر التصفية يسار البحث)."]],
        ["Aucune app compatible avec les architectures choisies ({0} masquées).", ["No app compatible with the chosen architectures ({0} hidden).", "Ninguna app compatible con las arquitecturas elegidas ({0} ocultas).", "Keine App mit den gewählten Architekturen kompatibel ({0} ausgeblendet).", "Nessuna app compatibile con le architetture scelte ({0} nascoste).", "Nenhum app compatível com as arquiteturas escolhidas ({0} ocultos).", "Нет совместимых приложений ({0} скрыто).", "Seçilen mimarilerle uyumlu uygulama yok ({0} gizli).", "لا تطبيق متوافق مع المعماريات المختارة ({0} مخفية)."]],
        ["{0} applications · OK : fiche · ▶ : installer · jaune : favori · appui long sur une icône : déplacer la rangée", ["{0} apps · OK: details · ▶: install · yellow: favorite · long press on an icon: move the row", "{0} apps · OK: ficha · ▶: instalar · amarillo: favorito · pulsación larga en un icono: mover la fila", "{0} Apps · OK: Details · ▶: installieren · Gelb: Favorit · Icon lange drücken: Zeile verschieben", "{0} app · OK: scheda · ▶: installa · giallo: preferito · pressione lunga su un'icona: sposta la riga", "{0} apps · OK: detalhes · ▶: instalar · amarelo: favorito · toque longo num ícone: mover a linha", "{0} приложений · OK: подробнее · ▶: установить · жёлтая: избранное · долгое нажатие на значок: переместить ряд", "{0} uygulama · OK: ayrıntılar · ▶: yükle · sarı: favori · simgeye uzun bas: satırı taşı", "{0} تطبيقاً · OK: التفاصيل · ▶: تثبيت · الأصفر: مفضلة · ضغط مطوّل على أيقونة: نقل الصف"]],
        ["Recherche dans : {0}", ["Searching in: {0}", "Buscando en: {0}", "Suche in: {0}", "Ricerca in: {0}", "Pesquisando em: {0}", "Поиск в: {0}", "Aranan yerler: {0}", "البحث في: {0}"]],
        ["Recherche : {0}…", ["Searching: {0}…", "Buscando: {0}…", "Suche: {0}…", "Ricerca: {0}…", "Pesquisando: {0}…", "Поиск: {0}…", "Aranıyor: {0}…", "جارٍ البحث: {0}…"]],
        ["Résultats : {0}", ["Results: {0}", "Resultados: {0}", "Ergebnisse: {0}", "Risultati: {0}", "Resultados: {0}", "Результаты: {0}", "Sonuçlar: {0}", "النتائج: {0}"]],
        ["Store · profil {0}", ["Store · profile {0}", "Store · perfil {0}", "Store · Profil {0}", "Store · profilo {0}", "Store · perfil {0}", "Store · профиль {0}", "Store · profil {0}", "المتجر · ملف {0}"]],
        ["Profil Store enregistré : {0}", ["Store profile saved: {0}", "Perfil Store guardado: {0}", "Store-Profil gespeichert: {0}", "Profilo Store salvato: {0}", "Perfil Store salvo: {0}", "Профиль Store сохранён: {0}", "Store profili kaydedildi: {0}", "تم حفظ ملف المتجر: {0}"]],
        ["{0} · {1} masquées (architecture)", ["{0} · {1} hidden (architecture)", "{0} · {1} ocultas (arquitectura)", "{0} · {1} ausgeblendet (Architektur)", "{0} · {1} nascoste (architettura)", "{0} · {1} ocultos (arquitetura)", "{0} · скрыто: {1} (архитектура)", "{0} · {1} gizli (mimari)", "{0} · {1} مخفية (المعمارية)"]],
        ["Code envoyé à {0}. Saisis-le ci-dessous.", ["Code sent to {0}. Enter it below.", "Código enviado a {0}. Introdúcelo abajo.", "Code an {0} gesendet. Gib ihn unten ein.", "Codice inviato a {0}. Inseriscilo qui sotto.", "Código enviado para {0}. Digite-o abaixo.", "Код отправлен на {0}. Введите его ниже.", "Kod {0} adresine gönderildi. Aşağıya gir.", "أُرسل الرمز إلى {0}. أدخله أدناه."]],
        ["Les apps sans architecture compatible sont masquées dans les rangées et dans les versions d'une fiche. Cette TV : {0}.", ["Apps without a compatible architecture are hidden in rows and in an app's versions. This TV: {0}.", "Las apps sin arquitectura compatible se ocultan en las filas y en las versiones de una ficha. Esta TV: {0}.", "Apps ohne kompatible Architektur werden in Zeilen und Versionen ausgeblendet. Dieser TV: {0}.", "Le app senza architettura compatibile sono nascoste nelle righe e nelle versioni. Questa TV: {0}.", "Apps sem arquitetura compatível ficam ocultos nas linhas e nas versões. Esta TV: {0}.", "Приложения без совместимой архитектуры скрыты в рядах и версиях. Этот ТВ: {0}.", "Uyumlu mimarisi olmayan uygulamalar satırlarda ve sürümlerde gizlenir. Bu TV: {0}.", "تُخفى التطبيقات غير المتوافقة في الصفوف والإصدارات. هذا التلفاز: {0}."]],
        ["{0} · vérifié", ["{0} · verified", "{0} · verificada", "{0} · verifiziert", "{0} · verificato", "{0} · verificada", "{0} · подтверждён", "{0} · doğrulandı", "{0} · موثّق"]],
        ["{0} app(s)", ["{0} app(s)", "{0} app(s)", "{0} App(s)", "{0} app", "{0} app(s)", "приложений: {0}", "{0} uygulama", "{0} تطبيق"]],
        ["profil {0}", ["profile {0}", "perfil {0}", "Profil {0}", "profilo {0}", "perfil {0}", "профиль {0}", "profil {0}", "ملف {0}"]],
        ["{0} avis", ["{0} reviews", "{0} reseñas", "{0} Bewertungen", "{0} recensioni", "{0} avaliações", "отзывов: {0}", "{0} yorum", "{0} مراجعة"]],
        ["{0} — OK pour les afficher", ["{0} — OK to show them", "{0} — OK para verlos", "{0} — OK zum Anzeigen", "{0} — OK per mostrarle", "{0} — OK para mostrar", "{0} — OK, чтобы показать", "{0} — göstermek için OK", "{0} — OK لعرضها"]],
        ["{0} par {1}", ["{0} by {1}", "{0} por {1}", "{0} von {1}", "{0} da {1}", "{0} por {1}", "{0}, {1}", "{0}, {1}", "{0} بواسطة {1}"]],
        ["Versions ({0})", ["Versions ({0})", "Versiones ({0})", "Versionen ({0})", "Versioni ({0})", "Versões ({0})", "Версии ({0})", "Sürümler ({0})", "الإصدارات ({0})"]],
        ["{0} version(s) incompatible(s) masquée(s)", ["{0} incompatible version(s) hidden", "{0} versión(es) incompatible(s) oculta(s)", "{0} inkompatible Version(en) ausgeblendet", "{0} versioni incompatibili nascoste", "{0} versão(ões) incompatível(is) oculta(s)", "Скрыто несовместимых версий: {0}", "{0} uyumsuz sürüm gizlendi", "أُخفي {0} إصدار غير متوافق"]],
        ["v{0} · affichée", ["v{0} · shown", "v{0} · mostrada", "v{0} · angezeigt", "v{0} · mostrata", "v{0} · exibida", "v{0} · открыта", "v{0} · gösteriliyor", "v{0} · معروض"]],
        ["{0} téléch.", ["{0} downloads", "{0} descargas", "{0} Downloads", "{0} download", "{0} downloads", "{0} загр.", "{0} indirme", "{0} تنزيل"]],
        ["Version plus récente installée ({0}). Désinstalle-la d'abord pour installer celle-ci.", ["A newer version is installed ({0}). Uninstall it first to install this one.", "Hay una versión más reciente instalada ({0}). Desinstálala primero para instalar esta.", "Neuere Version installiert ({0}). Deinstalliere sie zuerst, um diese zu installieren.", "È installata una versione più recente ({0}). Disinstallala prima per installare questa.", "Uma versão mais recente está instalada ({0}). Desinstale-a primeiro para instalar esta.", "Установлена более новая версия ({0}). Сначала удалите её.", "Daha yeni sürüm yüklü ({0}). Bunu yüklemek için önce onu kaldır.", "يوجد إصدار أحدث مثبّت ({0}). أزله أولاً لتثبيت هذا."]],
        ["Installée (v{0})", ["Installed (v{0})", "Instalada (v{0})", "Installiert (v{0})", "Installata (v{0})", "Instalado (v{0})", "Установлено (v{0})", "Yüklü (v{0})", "مثبّت (v{0})"]],
        ["Store de {0} ajouté à l'accueil", ["{0}'s Store added to the home screen", "Store de {0} añadido al inicio", "Store von {0} zum Startbildschirm hinzugefügt", "Store di {0} aggiunto alla home", "Store de {0} adicionada ao início", "Store {0} добавлен на главную", "{0} mağazası ana ekrana eklendi", "أضيف متجر {0} إلى الرئيسية"]],
        ["Store de {0}", ["{0}'s Store", "Store de {0}", "Store von {0}", "Store di {0}", "Store de {0}", "Store {0}", "{0} mağazası", "متجر {0}"]],
        ["Profil {0} · {1} app(s) · {2} masquée(s) (architecture)", ["Profile {0} · {1} app(s) · {2} hidden (architecture)", "Perfil {0} · {1} app(s) · {2} oculta(s) (arquitectura)", "Profil {0} · {1} App(s) · {2} ausgeblendet (Architektur)", "Profilo {0} · {1} app · {2} nascoste (architettura)", "Perfil {0} · {1} app(s) · {2} oculto(s) (arquitetura)", "Профиль {0} · приложений: {1} · скрыто: {2} (архитектура)", "Profil {0} · {1} uygulama · {2} gizli (mimari)", "الملف {0} · {1} تطبيق · {2} مخفية (المعمارية)"]],
        ["Profil {0} · {1} app(s)", ["Profile {0} · {1} app(s)", "Perfil {0} · {1} app(s)", "Profil {0} · {1} App(s)", "Profilo {0} · {1} app", "Perfil {0} · {1} app(s)", "Профиль {0} · приложений: {1}", "Profil {0} · {1} uygulama", "الملف {0} · {1} تطبيق"]],
        ["Profil {0}", ["Profile {0}", "Perfil {0}", "Profil {0}", "Profilo {0}", "Perfil {0}", "Профиль {0}", "Profil {0}", "الملف {0}"]],
        ["« {0} » veut se connecter à cette TV.", ["“{0}” wants to connect to this TV.", "«{0}» quiere conectarse a esta TV.", "„{0}“ möchte sich mit diesem TV verbinden.", "«{0}» vuole collegarsi a questa TV.", "“{0}” quer se conectar a esta TV.", "«{0}» хочет подключиться к этому ТВ.", "«{0}» bu TV'ye bağlanmak istiyor.", "«{0}» يريد الاتصال بهذا التلفاز."]],
        ["Portable associé : {0}", ["Phone paired: {0}", "Móvil vinculado: {0}", "Handy gekoppelt: {0}", "Telefono associato: {0}", "Celular pareado: {0}", "Телефон связан: {0}", "Telefon eşlendi: {0}", "تم إقران الهاتف: {0}"]],
        ["Installation demandée par {0}", ["Installation requested by {0}", "Instalación pedida por {0}", "Installation angefordert von {0}", "Installazione richiesta da {0}", "Instalação pedida por {0}", "Установка запрошена с {0}", "Kurulumu isteyen: {0}", "طلب التثبيت من {0}"]],
        ["Envoyé par {0}", ["Sent by {0}", "Enviado por {0}", "Gesendet von {0}", "Inviato da {0}", "Enviado por {0}", "Отправлено с {0}", "Gönderen: {0}", "أرسله {0}"]],
        ["{0} / {1} · ◀ ▶ pour défiler · Retour pour fermer", ["{0} / {1} · ◀ ▶ to browse · Back to close", "{0} / {1} · ◀ ▶ para pasar · Atrás para cerrar", "{0} / {1} · ◀ ▶ blättern · Zurück zum Schließen", "{0} / {1} · ◀ ▶ per scorrere · Indietro per chiudere", "{0} / {1} · ◀ ▶ para navegar · Voltar para fechar", "{0} / {1} · ◀ ▶ листать · Назад — закрыть", "{0} / {1} · gezinmek için ◀ ▶ · kapatmak için Geri", "{0} / {1} · ◀ ▶ للتصفح · رجوع للإغلاق"]],
    ];

    // ------------------------------------------------------------------ moteur

    function norm(s) { return s.replace(/\s+/g, ' ').trim(); }
    function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

    const PATTERNS = P.map(([src, tr]) => {
        const parts = src.split(/\{(\d)\}/);
        let re = '^';
        const order = [];
        parts.forEach((p, i) => {
            if (i % 2) { re += '(.*?)'; order.push(Number(p)); }
            else re += escRe(p);
        });
        return { re: new RegExp(re + '$'), order, tr, weight: src.replace(/\{\d\}/g, '').length };
    }).sort((a, b) => b.weight - a.weight);

    function stored() {
        try {
            return (window.Android && typeof window.Android.getPref === 'function' && window.Android.getPref('lang')) ||
                window.localStorage.getItem('tv_lang') || 'auto';
        } catch (e) { return 'auto'; }
    }

    function detect() {
        const nav = ((navigator.languages && navigator.languages[0]) || navigator.language || 'fr').slice(0, 2).toLowerCase();
        return LANGS.some(l => l[0] === nav) ? nav : 'en';
    }

    const choice = stored();
    const lang = choice && choice !== 'auto' && LANGS.some(l => l[0] === choice) ? choice : detect();
    const idx = ORDER.indexOf(lang);   // -1 = français (texte source)

    function exact(s) {
        if (idx < 0) return null;
        const e = D[s];
        return e ? e[idx] : null;
    }

    /** Traduit un texte français (exact ou modèle avec parties variables). */
    function t(s) {
        if (idx < 0 || !s) return s;
        const n = norm(s);
        const e = exact(n);
        if (e) return e === n ? s : e;
        for (const p of PATTERNS) {
            const m = n.match(p.re);
            if (!m) continue;
            let out = p.tr[idx];
            p.order.forEach((num, i) => {
                const v = m[i + 1];
                out = out.replace('{' + num + '}', exact(v) || v);
            });
            return out === n ? s : out;
        }
        return s;
    }

    // Zones de contenu (noms d'apps, descriptions, avis…) : jamais traduites ici
    const SKIP = '.app-name, .app-meta, .d-text, .review .txt, .account-name, .cell-name, .chip, .d-name, script, style, [data-no-i18n]';

    function translateNode(node) {
        if (node.nodeType === 3) {
            const v = node.nodeValue;
            if (!v || !v.trim()) return;
            const p = node.parentElement;
            if (p && p.closest(SKIP)) return;
            const tr = t(v);
            if (tr === v) return;
            // conserve les espaces de début/fin (mise en page des gabarits)
            const lead = v.match(/^\s*/)[0], trail = v.match(/\s*$/)[0];
            const next = lead + tr + trail;
            // n'écrit que si le texte change vraiment (sinon boucle de l'observateur)
            if (next !== v) node.nodeValue = next;
        } else if (node.nodeType === 1) {
            if (node.matches && node.matches('script, style')) return;
            ['placeholder', 'title', 'aria-label'].forEach(attr => {
                const v = node.getAttribute && node.getAttribute(attr);
                if (v) { const tr = t(v); if (tr !== v && tr !== v.replace(/\s+/g, ' ').trim()) node.setAttribute(attr, tr); }
            });
            node.childNodes.forEach(translateNode);
        }
    }

    function start() {
        document.documentElement.lang = lang;
        if (lang === 'ar') document.documentElement.classList.add('lang-rtl');
        if (idx < 0) return;
        translateNode(document.body);
        new MutationObserver(muts => {
            for (const m of muts) {
                if (m.type === 'characterData') translateNode(m.target);
                else if (m.type === 'attributes') translateNode(m.target);
                else m.addedNodes.forEach(translateNode);
            }
        }).observe(document.body, {
            subtree: true, childList: true, characterData: true,
            attributes: true, attributeFilter: ['placeholder', 'title', 'aria-label'],
        });
    }

    function setLang(code) {
        try {
            if (window.Android && typeof window.Android.setPref === 'function') window.Android.setPref('lang', code);
            else window.localStorage.setItem('tv_lang', code);
        } catch (e) { /* */ }
        // recharge : interface + contenu Appteka dans la nouvelle langue
        if (window.Android && typeof window.Android.reloadUi === 'function') window.Android.reloadUi();
        else window.location.reload();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();

    return {
        lang, choice, langs: LANGS, t, setLang,
        locale: { fr: 'fr-FR', en: 'en-GB', es: 'es-ES', de: 'de-DE', it: 'it-IT', pt: 'pt-PT', ru: 'ru-RU', tr: 'tr-TR', ar: 'ar' }[lang],
    };
})();
