#if DEBUG
import SwiftUI

/// A store-scene launch: builds the fixtures, draws the scene full screen
/// and marks it ready for the capture script.
struct StoreSceneRoot: View {
    let scene: StoreScene
    @State private var fixtures: StoreFixtures?

    var body: some View {
        ZStack {
            StoreCanvas.background
            if let fixtures {
                StoreSceneView(scene: scene)
                    .environment(fixtures.store)
                    .environment(fixtures.downloads)
                    .environment(fixtures.runtime)
                    .environment(fixtures.conversation)
                    .environment(fixtures.dictation)
                    .environment(\.chatStore, fixtures.chatStore)
            }
        }
        .ignoresSafeArea()
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .task {
            StoreSceneReady.clear()
            do {
                fixtures = try await StoreFixtures.make(for: scene)
            } catch {
                assertionFailure("Store scene fixtures: \(error)")
                return
            }
            // Let the chat scroll to its end, images decode and the
            // database observations deliver.
            try? await Task.sleep(for: .seconds(2))
            StoreSceneReady.mark(scene)
        }
    }
}

/// Canvas colours and the shared measurements, in points of the capture
/// device's screen.
enum StoreCanvas {
    static let background = Color(red: 0.04, green: 0.04, blue: 0.04)
    static let subtitle = Color(white: 0.6)

    /// The app is laid out at a real device's width (iPhone 17 Pro, 11-inch
    /// iPad), then scaled into the frame.
    static let phoneWidth: CGFloat = 402
    static let padWidth: CGFloat = 834

    @MainActor static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
}

struct StoreSceneView: View {
    let scene: StoreScene

    var body: some View {
        switch scene {
        case .hero:
            HeroScene()
        case .chooseModel:
            MarketingFrame(icon: "sparkles", title: "screenshot_choose_model", subtitle: "screenshot_choose_model_sub") {
                AppScreen { ChatStack(showsPicker: true) }
            }
        case .chat:
            MarketingFrame(icon: "wifi.slash", title: "screenshot_chat", subtitle: "screenshot_chat_sub") {
                AppScreen { ChatStack() }
            }
        case .documents:
            MarketingFrame(icon: "doc.text", title: "screenshot_documents", subtitle: "screenshot_documents_sub") {
                AppScreen { ChatStack() }
            }
        case .chatWithImage:
            MarketingFrame(icon: "photo.badge.plus", title: "screenshot_vision", subtitle: "screenshot_vision_sub") {
                AppScreen { ChatStack() }
            }
        case .systemPrompts:
            MarketingFrame(icon: "text.quote", title: "screenshot_prompts", subtitle: "screenshot_prompts_sub") {
                AppScreen { SheetOverChat { NavigationStack { SystemPromptsView().navigationBarTitleDisplayMode(.inline) } } }
            }
        case .tools:
            MarketingFrame(icon: "wrench.and.screwdriver", title: "screenshot_tools", subtitle: "screenshot_tools_sub") {
                AppScreen { SheetOverChat { NavigationStack { ToolsSettingsView().navigationBarTitleDisplayMode(.inline) } } }
            }
        case .generationParams:
            MarketingFrame(icon: "slider.horizontal.3", title: "screenshot_fine_tune", subtitle: "screenshot_fine_tune_sub") {
                AppScreen { SheetOverChat { GenerationParamsSheet() } }
            }
        }
    }
}

// MARK: - Marketing layout

/// Icon, headline and subtitle over a device whose lower edge runs off the
/// canvas (Android: StoreScreenshotFrame).
private struct MarketingFrame<Screen: View>: View {
    let icon: String
    let title: String
    let subtitle: String
    @ViewBuilder var screen: Screen

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            // One unit is a point on a phone; the iPad's text grows with it.
            let unit = min(size.width, size.height * 0.46) / StoreCanvas.phoneWidth
            let deviceTop = (StoreCanvas.isPad ? 250 : 232) * unit
            VStack(spacing: 0) {
                VStack(spacing: 12 * unit) {
                    Image(systemName: icon)
                        .font(.system(size: 28 * unit))
                        .foregroundStyle(.tint)
                    Text(verbatim: storeText(title))
                        .font(.system(size: 34 * unit, design: .serif))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(verbatim: storeText(subtitle))
                        .font(.system(size: 17 * unit))
                        .foregroundStyle(StoreCanvas.subtitle)
                        .lineLimit(2)
                }
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 24 * unit)
                .frame(height: deviceTop - 24 * unit)
                .padding(.top, 8 * unit)

                Spacer(minLength: 16 * unit)
                Device(height: size.height - deviceTop) { screen }
            }
            .frame(width: size.width, height: size.height)
        }
        .background(StoreCanvas.background)
        .clipped()
    }
}

/// A device bezel around `screen`, scaled to `height` of the canvas; the
/// bottom of the bezel lies beyond the canvas edge.
private struct Device<Screen: View>: View {
    let height: CGFloat
    @ViewBuilder var screen: Screen

    var body: some View {
        GeometryReader { geometry in
            let width = StoreCanvas.isPad ? StoreCanvas.padWidth : StoreCanvas.phoneWidth
            let bezel: CGFloat = StoreCanvas.isPad ? 22 : 12
            let fraction: CGFloat = StoreCanvas.isPad ? 0.86 : 0.84
            let scale = geometry.size.width * fraction / (width + 2 * bezel)
            let screenHeight = height / scale - bezel
            let outer: CGFloat = StoreCanvas.isPad ? 52 : 64
            screen
                .frame(width: width, height: screenHeight)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: outer - bezel, topTrailingRadius: outer - bezel))
                .padding([.top, .horizontal], bezel)
                // The bezel's lower edge stays below the canvas.
                .padding(.bottom, 80)
                .background(
                    UnevenRoundedRectangle(topLeadingRadius: outer, topTrailingRadius: outer)
                        .fill(.black)
                        .overlay(UnevenRoundedRectangle(topLeadingRadius: outer, topTrailingRadius: outer)
                            .strokeBorder(Color(white: 0.32), lineWidth: 3))
                )
                .scaleEffect(scale, anchor: .top)
                .frame(width: geometry.size.width, height: geometry.size.height, alignment: .top)
        }
        .frame(height: height)
    }
}

/// The app's screen inside the device: a fake status bar, the app, and a
/// plain strip under it. The system home indicator can't be hidden for sure
/// in time, so it lands on the strip, where the capture script paints it out.
private struct AppScreen<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) {
            FakeStatusBar()
            content
            Color.black.frame(height: 24)
        }
        .background(Color(.systemBackground))
        .environment(\.horizontalSizeClass, StoreCanvas.isPad ? .regular : .compact)
    }
}

/// 9:41, full signal and battery; a Dynamic Island on the phone.
private struct FakeStatusBar: View {
    var body: some View {
        let pad = StoreCanvas.isPad
        HStack {
            Text(verbatim: "9:41")
                .font(.system(size: pad ? 15 : 17, weight: .semibold))
            Spacer()
            HStack(spacing: 6) {
                Image(systemName: "cellularbars")
                Image(systemName: "wifi")
                Image(systemName: "battery.100percent")
                    .font(.system(size: pad ? 17 : 19))
            }
            .font(.system(size: pad ? 14 : 15, weight: .semibold))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, pad ? 24 : 34)
        .padding(.top, pad ? 6 : 16)
        .frame(height: pad ? 32 : 54, alignment: .top)
        .overlay(alignment: .top) {
            if !pad {
                Capsule().fill(.black)
                    .frame(width: 124, height: 36)
                    .padding(.top, 11)
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }
}

// MARK: - App content

/// The chat as RootView lays it out: a navigation stack on the phone, the
/// chats sidebar beside it on the iPad. Optionally with the model picker open.
private struct ChatStack: View {
    var showsPicker = false

    var body: some View {
        if StoreCanvas.isPad {
            NavigationSplitView(columnVisibility: .constant(.all)) {
                SessionsView(showSettings: .constant(false))
            } detail: {
                chat
            }
            .navigationSplitViewStyle(.balanced)
        } else {
            chat.environment(\.openSidebar, OpenSidebarAction {})
        }
    }

    private var chat: some View {
        NavigationStack {
            ConversationView(showPicker: .constant(showsPicker), showParams: .constant(false),
                             showLibrary: .constant(false))
        }
        .overlay {
            if showsPicker {
                ModelPickerCard(isPresented: .constant(true), onTooLarge: { _, _ in }, onBrowse: {}, onParams: {})
            }
        }
    }
}

/// A sheet over the dimmed chat. Real presentations would leave the device
/// frame, so the sheet is drawn in place: a large sheet on the phone, a
/// centered form sheet on the iPad.
private struct SheetOverChat<Sheet: View>: View {
    @ViewBuilder var sheet: Sheet
    @Environment(ConversationModel.self) private var conversation
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    @Environment(DownloadManager.self) private var downloads
    @Environment(DictationController.self) private var dictation
    @Environment(\.chatStore) private var chatStore

    var body: some View {
        ZStack {
            ChatStack()
            Color.black.opacity(0.45)
            if StoreCanvas.isPad {
                panel
                    .frame(width: 540, height: 700)
                    .clipShape(RoundedRectangle(cornerRadius: 38))
                    .overlay(RoundedRectangle(cornerRadius: 38).strokeBorder(Color(white: 0.24)))
            } else {
                panel
                    .clipShape(UnevenRoundedRectangle(topLeadingRadius: 38, topTrailingRadius: 38))
                    .padding(.top, 52)
            }
        }
    }

    /// The sheet at the elevated interface level, as a presented sheet is,
    /// so the system backgrounds take their lighter sheet shades. The hosted
    /// view starts a new environment, so the services are passed on.
    private var panel: some View {
        ElevatedLevel {
            VStack(spacing: 0) {
                if !StoreCanvas.isPad {
                    Capsule().fill(.secondary).frame(width: 36, height: 5).padding(.vertical, 6)
                }
                sheet
            }
            .background(Color(.systemGroupedBackground))
            .environment(conversation)
            .environment(runtime)
            .environment(store)
            .environment(downloads)
            .environment(dictation)
            .environment(\.chatStore, chatStore)
        }
    }
}

/// Hosts its content with the elevated user interface level trait, which
/// SwiftUI has no environment value for.
private struct ElevatedLevel<Content: View>: UIViewControllerRepresentable {
    @ViewBuilder var content: Content

    func makeUIViewController(context: Context) -> UIHostingController<Content> {
        let controller = UIHostingController(rootView: content)
        controller.traitOverrides.userInterfaceLevel = .elevated
        controller.view.backgroundColor = .clear
        return controller
    }

    func updateUIViewController(_ controller: UIHostingController<Content>, context: Context) {
        controller.rootView = content
    }
}

// MARK: - Hero

/// Logo, headline and the model makers (Android: scene0_hero).
private struct HeroScene: View {
    private static let logos = ["logo_google", "logo_meta", "logo_qwen", "logo_microsoft", "logo_mistral", "logo_nvidia"]

    var body: some View {
        GeometryReader { geometry in
            let unit = min(geometry.size.width, geometry.size.height * 0.46) / StoreCanvas.phoneWidth
            VStack(spacing: 0) {
                Spacer().frame(height: 110 * unit)
                Image("penrose_triangle")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120 * unit, height: 120 * unit)
                Spacer().frame(height: 44 * unit)
                Text(title)
                    .font(.system(size: 50 * unit, design: .serif))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4 * unit)
                    .lineLimit(2)
                    .minimumScaleFactor(0.5)
                Spacer().frame(height: 26 * unit)
                Text(verbatim: storeText("screenshot_hero_subtitle"))
                    .font(.system(size: 20 * unit, weight: .medium))
                    .foregroundStyle(Color(white: 0.73))
                Spacer()
                Text(verbatim: storeText("screenshot_hero_providers_label"))
                    .font(.system(size: 14 * unit))
                    .foregroundStyle(StoreCanvas.subtitle)
                Spacer().frame(height: 20 * unit)
                HStack(spacing: -20 * unit) {
                    ForEach(Self.logos, id: \.self) { logo in
                        Image(logo)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 54 * unit, height: 54 * unit)
                            .background(.white)
                            .clipShape(Circle())
                            .padding(5 * unit)
                            .background(StoreCanvas.background, in: Circle())
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                Spacer().frame(height: 110 * unit)
            }
            .padding(.horizontal, 28 * unit)
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .background(StoreCanvas.background)
    }

    /// "Run models on / *your* phone", the middle word in italics.
    private var title: AttributedString {
        let suffix = storeText(StoreCanvas.isPad ? "screenshot_hero_title_suffix_tablet" : "screenshot_hero_title_suffix")
        var emphasis = AttributedString(storeText("screenshot_hero_title_emphasis"))
        emphasis.inlinePresentationIntent = .emphasized
        return AttributedString(storeText("screenshot_hero_title_prefix") + "\n") + emphasis + AttributedString(" " + suffix)
    }
}
#endif
