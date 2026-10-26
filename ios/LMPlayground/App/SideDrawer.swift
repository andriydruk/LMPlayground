import SwiftUI

/// The iPhone sidebar: hidden behind the menu button, 80% of the screen wide,
/// and pushing the chat aside as it opens (Android: ModalNavigationDrawer).
/// It follows the finger from the left edge, and a drag back or a tap on the
/// chat closes it.
struct SideDrawer<Sidebar: View, Content: View>: View {
    @Binding var isOpen: Bool
    @ViewBuilder var sidebar: Sidebar
    @ViewBuilder var content: Content

    @GestureState private var drag: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let width = (geometry.size.width * 0.8).rounded()
            let offset = min(max((isOpen ? width : 0) + drag, 0), width)
            let progress = offset / width

            ZStack(alignment: .leading) {
                // The stack ignores the safe area so the chat's rounded corners
                // reach the screen's; the sidebar keeps clear of the status bar
                // and home indicator itself.
                sidebar
                    .padding(.top, geometry.safeAreaInsets.top)
                    .padding(.bottom, geometry.safeAreaInsets.bottom)
                    .frame(width: width)
                    .offset(x: (offset - width) / 3)
                    .opacity(0.4 + 0.6 * progress)
                    .simultaneousGesture(dragGesture(width: width))

                content
                    .frame(width: geometry.size.width)
                    .overlay {
                        // Takes taps and drags on the pushed-aside chat.
                        if isOpen || progress > 0 {
                            Color.black.opacity(0.25 * progress)
                                .contentShape(.rect)
                                .onTapGesture { setOpen(false) }
                                .gesture(dragGesture(width: width))
                                .accessibilityLabel("Close")
                                .accessibilityAddTraits(.isButton)
                        }
                    }
                    .clipShape(.rect(cornerRadius: 40 * progress))
                    .shadow(color: .black.opacity(0.3 * progress), radius: 16)
                    .offset(x: offset)
                    .overlay(alignment: .leading) {
                        // The edge the drawer is pulled out from; narrow, and
                        // clear of the menu button, so it never takes a tap.
                        if !isOpen {
                            Color.clear
                                .frame(width: 12)
                                .padding(.top, 120)
                                .padding(.bottom, 100)
                                .contentShape(.rect)
                                .gesture(dragGesture(width: width))
                        }
                    }
            }
            .ignoresSafeArea(.container, edges: .vertical)
        }
        .onChange(of: isOpen) { _, open in
            if open {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            }
        }
    }

    private func dragGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 10)
            .updating($drag) { value, state, _ in
                // Horizontal drags only: vertical ones scroll the list.
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                state = value.translation.width
            }
            .onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                let projected = (isOpen ? width : 0) + value.predictedEndTranslation.width
                setOpen(projected > width / 2)
            }
    }

    private func setOpen(_ open: Bool) {
        withAnimation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.3)) { isOpen = open }
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
