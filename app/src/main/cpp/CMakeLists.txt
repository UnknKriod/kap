cmake_minimum_required(VERSION 3.22.1)

project("kapclient")

# 1. Определяем путь к папке с библиотеками внутри проекта
set(LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 2. Создаем нашу JNI-обертку
add_library(kapcore SHARED kapcore.cpp)

# 3. Находим системные библиотеки
find_library(log-lib log)
find_library(android-lib android)

# 4. Указываем линкеру, где искать сторонние библиотеки (только для сборки)
target_link_directories(kapcore PRIVATE ${LIBS_DIR})

# 5. Связываем по ИМЕНИ.
# Линкер запишет в заголовок только "libkapgo.so",
# и Android найдет её в папке библиотек приложения.
target_link_libraries(kapcore
    kapgo
    ${log-lib}
    ${android-lib}
)
