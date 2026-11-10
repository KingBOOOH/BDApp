package fr.noe.bda.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.noe.bda.BuildConfig
import fr.noe.bda.data.lookup.BookLookup
import fr.noe.bda.data.lookup.BookSearch
import fr.noe.bda.data.lookup.DefaultBookLookup
import fr.noe.bda.data.lookup.DefaultBookSearch
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksApi
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksApiKey
import fr.noe.bda.data.lookup.googlebooks.GoogleBooksSource
import fr.noe.bda.data.lookup.openlibrary.OpenLibraryApi
import fr.noe.bda.data.lookup.openlibrary.OpenLibrarySource
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LookupModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOpenLibraryApi(client: OkHttpClient, json: Json): OpenLibraryApi =
        retrofit("https://openlibrary.org/", client, json).create()

    @Provides
    @Singleton
    fun provideGoogleBooksApi(client: OkHttpClient, json: Json): GoogleBooksApi =
        retrofit("https://www.googleapis.com/books/v1/", client, json).create()

    @Provides
    @GoogleBooksApiKey
    fun provideGoogleBooksApiKey(): String = BuildConfig.GOOGLE_BOOKS_API_KEY

    @Provides
    fun provideBookLookup(openLibrary: OpenLibrarySource, googleBooks: GoogleBooksSource): BookLookup =
        DefaultBookLookup(primary = openLibrary, fallback = googleBooks)

    @Provides
    fun provideBookSearch(openLibrary: OpenLibrarySource, googleBooks: GoogleBooksSource): BookSearch =
        DefaultBookSearch(primary = openLibrary, fallback = googleBooks)

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
