import PhotosUI
import SwiftUI

/// "+" menu: a document for any model; a photo from the library or the
/// camera for vision models.
struct AttachmentMenu: View {
    @Environment(ConversationModel.self) private var model
    let allowsImages: Bool
    @State private var pickerItem: PhotosPickerItem?
    @State private var showLibrary = false
    @State private var showCamera = false
    @State private var showDocuments = false

    var body: some View {
        Menu {
            Button("Document", systemImage: "doc.text") { showDocuments = true }
            if allowsImages {
                Button("Photo Library", systemImage: "photo.on.rectangle") { showLibrary = true }
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button("Camera", systemImage: "camera") { showCamera = true }
                }
            }
        } label: {
            Image(systemName: "plus")
                .font(.title3)
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .accessibilityLabel("Attach")
        .accessibilityIdentifier("attachButton")
        .fileImporter(isPresented: $showDocuments, allowedContentTypes: DocumentText.openableTypes) { result in
            if case .success(let url) = result { model.attachDocument(url) }
        }
        .photosPicker(isPresented: $showLibrary, selection: $pickerItem, matching: .images)
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            pickerItem = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    model.attachImage(data)
                }
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { data in
                if let data { model.attachImage(data) }
                showCamera = false
            }
            .ignoresSafeArea()
        }
    }
}

/// The staged image above the text field, removable until sent.
struct PendingImagePreview: View {
    @Environment(ConversationModel.self) private var model
    let path: String

    var body: some View {
        HStack {
            ZStack(alignment: .topTrailing) {
                StoredImage(path: path)
                    .frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                Button("Remove image", systemImage: "xmark.circle.fill") { model.discardPendingImage() }
                    .labelStyle(.iconOnly)
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.6))
                    .padding(4)
            }
            Spacer()
        }
        .padding(.horizontal)
    }
}

/// An image from ChatImageStore, loaded off the main thread.
struct StoredImage: View {
    let path: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Rectangle().fill(.quaternary)
            }
        }
        .task(id: path) {
            let url = ChatImageStore.url(for: path)
            image = await Task.detached { UIImage(contentsOfFile: url.path)?.preparingForDisplay() }.value
        }
    }
}

/// Full-screen view of a chat image.
struct ImageViewer: View {
    let path: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            StoredImage(path: path)
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black)
                .toolbar { Button("Done") { dismiss() } }
                .toolbarBackground(.visible, for: .navigationBar)
        }
    }
}

/// UIKit camera capture (SwiftUI has no camera view).
struct CameraPicker: UIViewControllerRepresentable {
    var onFinish: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (Data?) -> Void

        init(onFinish: @escaping (Data?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let image = info[.originalImage] as? UIImage
            onFinish(image?.jpegData(compressionQuality: 0.9))
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}
