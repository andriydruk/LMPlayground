import SwiftUI

/// The iPhone sidebar: hidden behind the menu button, 80% of the screen wide,
/// and pushing the chat aside as it opens (Android: ModalNavigationDrawer).
/// It follows the finger from the left edge, and a drag back or a tap on the
/// chat closes it.
struct SideDrawer<Sidebar: View, Content: View>: View {
    @Binding var isOpen: Bool
    @ViewBuilder var sidebar: Sidebar
    @ViewBuilder var content: Content

    /// The finger's travel while a drag is under way.
    @State private var drag: CGFloat = 0
    /// Set once a drag is known to be horizontal; vertical ones scroll.
    @State private var dragging = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let width = (geometry.size.width * 0.8).rounded()
            let offset = min(max((isOpen ? width : 0) + drag, 0), width)
            let progress = offset / width

            ZStack(alignment: .leading) {
                // A shade apart from the chat, so the pushed-aside chat's edge
                // shows in dark mode too, where its shadow and dimming vanish.
                Color(.secondarySystemBackground)
                    .allowsHitTesting(false)

                // The stack ignores the safe area so the chat's rounded corners
                // reach the screen's; the sidebar keeps clear of the status bar
                // and home indicator itself.
                sidebar
                    .padding(.top, geometry.safeAreaInsets.top)
                    .padding(.bottom, geometry.safeAreaInsets.bottom)
                    .frame(width: width)
                    .offset(x: (offset - width) / 3)
                    .opacity(0.4 + 0.6 * progress)

                content
                    .frame(width: geometry.size.width)
                    .overlay {
                        // Takes taps on the pushed-aside chat.
                        if isOpen || progress > 0 {
                            Color.black.opacity(0.25 * progress)
                                .contentShape(.rect)
                                .onTapGesture { setOpen(false) }
                                .accessibilityLabel("Close")
                                .accessibilityAddTraits(.isButton)
                        }
                    }
                    .clipShape(.rect(cornerRadius: 40 * progress))
                    .shadow(color: .black.opacity(0.3 * progress), radius: 16)
                    .offset(x: offset)
            }
            // One drag for the whole drawer: separate ones on the sidebar and
            // the chat could both track a single swipe and fight over it.
            .simultaneousGesture(dragGesture(width: width))
            .ignoresSafeArea(.container, edges: .vertical)
        }
        .onChange(of: isOpen) { _, open in
            if open { UIApplication.shared.dismissKeyboard() }
        }
    }

    private func dragGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { value in
                if !dragging {
                    // Closed, it is pulled out from the left edge only, so
                    // the chat keeps its own drags.
                    guard isOpen || value.startLocation.x < 20 else { return }
                    // Decide once, at the start: a drag that wobbles later
                    // keeps following the finger.
                    guard abs(value.translation.width) > abs(value.translation.height) else { return }
                    dragging = true
                }
                drag = value.translation.width
            }
            .onEnded { value in
                guard dragging else { return }
                dragging = false
                let projected = (isOpen ? width : 0) + value.predictedEndTranslation.width
                // The travel is let go in the same animation, so the drawer
                // carries on from under the finger rather than jumping back.
                withAnimation(animation) {
                    isOpen = projected > width / 2
                    drag = 0
                }
            }
    }

    private var animation: Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.3)
    }

    private func setOpen(_ open: Bool) {
        withAnimation(animation) { isOpen = open }
    }
}

/// Opens the drawer from inside the chat; nil where the sidebar is always
/// shown (iPad).
struct OpenSidebarAction {
    let action: () -> Void
    func callAsFunction() { action() }
}

extension EnvironmentValues {
    @Entry var openSidebar: OpenSidebarAction?
}
