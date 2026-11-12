package com.example.localchat.llm

/** A downloadable `.litertlm` model (LiteRT-LM format, hosted by the LiteRT Community on Hugging Face). */
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeLabel: String,
    val url: String,
    val fileName: String,
)

/**
 * Models offered in the app. Any other `.litertlm` file can still be added with
 * "Nhập từ tệp" or "Tải từ link" on the models screen.
 */
object ModelCatalog {
    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            description = "Chất lượng tốt nhất trong danh sách. Nên dùng máy có RAM từ 8 GB trở lên.",
            sizeLabel = "2,58 GB",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
        ),
        CatalogModel(
            id = "gemma-3-1b-int4",
            name = "Gemma 3 1B (int4)",
            description = "Nhẹ và nhanh, hợp máy 4–6 GB RAM. Mô hình Gemma có thể yêu cầu chấp nhận " +
                "điều khoản trên Hugging Face và nhập access token ở mục bên dưới.",
            sizeLabel = "≈ 0,53 GB",
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileName = "gemma3-1b-it-int4.litertlm",
        ),
    )
}
