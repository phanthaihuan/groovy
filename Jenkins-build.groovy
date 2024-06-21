pipeline {
    agent any

    parameters {
        string(name: 'MILU2_INFRA_GIT_BRANCH', defaultValue: 'main', description: 'Git branch storing Terraform.')
    }

    environment {
        AWS_ACCESS_KEY_ID     = credentials('aws-secret-key-id')
        AWS_SECRET_ACCESS_KEY = credentials('aws-secret-access-key')
        GIT_CREDENTIAL = 'github-access'
        INSTALLER_DIR = 'terraform'
    }

    stages {
        stage('Checkout SCM') {
            steps {
                deleteDir()
                dir(INSTALLER_DIR) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: MILU2_INFRA_GIT_BRANCH]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            [
                                credentialsId: GIT_CREDENTIAL,
                                url: 'git@github.com:phanthaihuan/terraform2024.git'
                            ]
                        ]
                    ])
                }
            }
        }

        stage('Init Provider') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'pwd'
                    sh 'ls -lrth'
                    sh 'terraform init'
                }
            }
        }

        stage('Validate configuration files') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform validate'
                    sh 'if [ $? -ne 0 ]; then exit 1; fi'
                }
            }
        }

        stage('Plan Resources') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform plan'
                }
            }
        }

        stage('Apply Resources') {
            steps {
                script {
                    dir(INSTALLER_DIR) {
                        def userInput = input(
                            id: 'userInput',
                            message: 'Do you want to proceed for production deployment?',
                            parameters: [
                                string(
                                    name: 'INPUT_VALUE',
                                    defaultValue: '',
                                    description: 'Please enter YES or NO'
                                )
                            ]
                        )

                        if (userInput.trim() == 'YES') {
                            sh 'terraform apply -auto-approve'
                        } else {
                            currentBuild.result = 'ABORTED'
                            return
                        }
                    }
                }
            }
        }
    }
}
